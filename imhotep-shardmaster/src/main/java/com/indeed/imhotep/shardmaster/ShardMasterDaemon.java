package com.indeed.imhotep.shardmaster;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.indeed.imhotep.ZkEndpointPersister;
import com.indeed.imhotep.client.CheckpointedHostsReloader;
import com.indeed.imhotep.client.Host;
import com.indeed.imhotep.client.HostsReloader;
import com.indeed.imhotep.client.ZkHostsReloader;
import com.indeed.imhotep.fs.RemoteCachingFileSystemProvider;
import com.indeed.imhotep.fs.RemoteCachingPath;
import com.indeed.imhotep.fs.sql.SchemaInitializer;
import com.indeed.imhotep.shardmaster.db.shardinfo.Tables;
import com.indeed.imhotep.shardmaster.rpc.MultiplexingRequestHandler;
import com.indeed.imhotep.shardmaster.rpc.RequestResponseServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;
import org.joda.time.Duration;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

/**
 * @author kenh
 */

public class ShardMasterDaemon {
    private static final Logger LOGGER = Logger.getLogger(ShardMasterDaemon.class);
    private final Config config;
    private volatile RequestResponseServer server;

    public ShardMasterDaemon(final Config config) {
        this.config = config;
    }

    public void run() throws IOException, ExecutionException, InterruptedException, KeeperException, SQLException {
        LOGGER.info("Starting daemon...");

        final ExecutorService executorService = config.createExecutorService();
        final Timer timer = new Timer(DatasetShardAssignmentRefresher.class.getSimpleName());
        try (HikariDataSource dataSource = config.createDataSource()) {
            new SchemaInitializer(dataSource).initialize(Collections.singletonList(Tables.TBLSHARDASSIGNMENTINFO));

            final ShardAssignmentInfoDao shardAssignmentInfoDao = new ShardAssignmentInfoDao(dataSource, config.getStalenessThreshold());

            final RemoteCachingPath dataSetsDir = (RemoteCachingPath) Paths.get(RemoteCachingFileSystemProvider.URI);

            LOGGER.info("Reloading all daemon hosts");
            final HostsReloader hostsReloader = new CheckpointedHostsReloader(
                    new File(config.getHostsFile()),
                    config.createHostsReloader(),
                    config.getHostsDropRatio());

            hostsReloader.run();

            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    hostsReloader.run();
                }
            }, Duration.standardMinutes(1).getMillis(), Duration.standardMinutes(1).getMillis());

            final DatasetShardAssignmentRefresher refresher = new DatasetShardAssignmentRefresher(
                    dataSetsDir,
                    config.getShardFilter(),
                    executorService,
                    hostsReloader,
                    config.createAssigner(),
                    shardAssignmentInfoDao
            );

            LOGGER.info("Initializing all shard assignments");
            refresher.initialize();

            timer.schedule(refresher, config.getRefreshInterval().getMillis(), config.getRefreshInterval().getMillis());

            server = new RequestResponseServer(config.getServerPort(), new MultiplexingRequestHandler(
                    new ShardMasterServer(shardAssignmentInfoDao)
            ));
            try (ZkEndpointPersister endpointPersister = new ZkEndpointPersister(config.zkNodes, "/imhotep/shardmasters",
                         new Host(InetAddress.getLocalHost().getCanonicalHostName(), server.getActualPort()))
            ) {
                LOGGER.info("Starting request response server");
                server.run();
            } finally {
                server.close();
            }
        } finally {
            timer.cancel();
            executorService.shutdown();
        }
    }

    @VisibleForTesting
    void shutdown() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    static class Config {
        String zkNodes;
        String dbFile;
        String hostsFile;
        private ShardFilter shardFilter = ShardFilter.ACCEPT_ALL;
        int serverPort = 0;
        int threadPoolSize = 5;
        int replicationFactor = 3;
        Duration refreshInterval = Duration.standardMinutes(15);
        Duration stalenessThreshold = Duration.standardHours(1);
        double hostsDropRatio = 0.5;

        public Config setZkNodes(final String zkNodes) {
            this.zkNodes = zkNodes;
            return this;
        }

        public Config setDbFile(final String dbFile) {
            this.dbFile = dbFile;
            return this;
        }

        public Config setHostsFile(final String hostsFile) {
            this.hostsFile = hostsFile;
            return this;
        }

        public Config setShardFilter(final ShardFilter shardFilter) {
            this.shardFilter = shardFilter;
            return this;
        }

        public Config setServerPort(final int serverPort) {
            this.serverPort = serverPort;
            return this;
        }

        public Config setThreadPoolSize(final int threadPoolSize) {
            this.threadPoolSize = threadPoolSize;
            return this;
        }

        public Config setReplicationFactor(final int replicationFactor) {
            this.replicationFactor = replicationFactor;
            return this;
        }

        public Config setRefreshInterval(final long refreshInterval) {
            this.refreshInterval = Duration.millis(refreshInterval);
            return this;
        }

        public Config setStalenessThreshold(final long stalenessThreshold) {
            this.stalenessThreshold = Duration.millis(stalenessThreshold);
            return this;
        }

        public Config setHostsDropRatio(final double hostsDropRatio) {
            this.hostsDropRatio = hostsDropRatio;
            return this;
        }

        HostsReloader createHostsReloader() {
            Preconditions.checkNotNull(zkNodes, "ZooKeeper nodes config is missing");
            return new ZkHostsReloader(zkNodes, false);
        }

        ExecutorService createExecutorService() {
            return ScanWorkExecutors.newBlockingFixedThreadPool(threadPoolSize);
        }

        HikariDataSource createDataSource() {
            Preconditions.checkNotNull(dbFile, "DBFile config is missing");
            final HikariConfig dbConfig = new HikariConfig();
            // this is a bit arbitrary but we need to ensure that the pool size is large enough for the # of threads
            dbConfig.setMaximumPoolSize(Math.max(10, threadPoolSize + 1));
            dbConfig.setJdbcUrl("jdbc:h2:" + dbFile);
            return new HikariDataSource(dbConfig);
        }

        int getServerPort() {
            return serverPort;
        }

        ShardFilter getShardFilter() {
            return shardFilter;
        }

        ShardAssigner createAssigner() {
            return new MinHashShardAssigner(replicationFactor);
        }

        Duration getRefreshInterval() {
            return refreshInterval;
        }

        Duration getStalenessThreshold() {
            return stalenessThreshold;
        }

        String getHostsFile() {
            Preconditions.checkNotNull(hostsFile, "HostsFile config is missing");
            return hostsFile;
        }

        double getHostsDropRatio() {
            return hostsDropRatio;
        }
    }

    public static void main(final String[] args) throws InterruptedException, ExecutionException, IOException, KeeperException, SQLException {
        RemoteCachingFileSystemProvider.newFileSystem();

        new ShardMasterDaemon(new Config()
                .setZkNodes(System.getProperty("imhotep.shardmaster.zookeeper.nodes"))
                .setDbFile(System.getProperty("imhotep.shardmaster.db.file"))
                .setHostsFile(System.getProperty("imhotep.shardmaster.hosts.file"))
                .setServerPort(Integer.parseInt(System.getProperty("imhotep.shardmaster.server.port")))
        ).run();
    }
}
