package cc.lovezhy.raft.server;

import cc.lovezhy.raft.rpc.*;
import cc.lovezhy.raft.server.log.*;
import cc.lovezhy.raft.server.node.NodeConfig;
import cc.lovezhy.raft.server.node.NodeId;
import cc.lovezhy.raft.server.node.PeerRaftNode;
import cc.lovezhy.raft.server.node.RaftNode;
import cc.lovezhy.raft.server.utils.Pair;
import com.alibaba.fastjson.JSON;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static cc.lovezhy.raft.server.RaftConstants.getRandomStartElectionTimeout;
import static cc.lovezhy.raft.server.utils.ReflectUtils.getObjectMember;

public class ClusterManager implements Mock6824Config, ClusterChangeConfig {

    private static final Logger log = LoggerFactory.getLogger(ClusterManager.class);

    public static ClusterManager newCluster(int servers, boolean unreliable) {
        ClusterManager clusterManager = new ClusterManager();
        clusterManager.makeCluster(servers, unreliable);
        for (NodeId nodeId : clusterManager.fetchAllNodeId()) {
            clusterManager.setNetStatus(nodeId, true);
        }
        return clusterManager;
    }

    private int servers;
    private List<RaftNode> raftNodes;
    private Map<NodeId, RaftNode> nodeIdRaftNodeMap = Maps.newHashMap();
    private Map<NodeId, RaftNodeExtConfig> raftNodeExtConfigMap = Maps.newHashMap();
    private Map<NodeId, Properties> raftNodeStartProperties = Maps.newHashMap();
    private Map<NodeId, SavedNodeStatus> raftNodeSavedNodeStatus = Maps.newHashMap();
    private boolean reliable;
    private AtomicInteger nodeRpcPortAllocator;

    @Override
    public ClusterConfCommand newAddNodeCommand() {
        int nextNodeId = this.servers++;
        int nextRpcPort = nodeRpcPortAllocator.getAndAdd(3);
//        NodeId leaderNodeId = checkOneLeader();
//        RaftNode leaderRaftNode = nodeIdRaftNodeMap.get(leaderNodeId);
        NodeConfig nodeConfig = NodeConfig.create(NodeId.create(nextNodeId), EndPoint.create("localhost", nextRpcPort));
        Properties properties = new Properties();
        properties.setProperty("local", "localhost:" + nextRpcPort + ":" + nextNodeId);
//        properties.setProperty("peer", "localhost:" + leaderRaftNode.getEndPoint().getPort()+":" + leaderNodeId.getPeerId());
        RaftNode newNode = new RaftStarter().start(properties);
        newNode.init();

        List<NodeConfig> nodeConfigs = Lists.newArrayList();
        for (RaftNode node : nodeIdRaftNodeMap.values()) {
            nodeConfigs.add(NodeConfig.create(node.getNodeId(), node.getEndPoint()));
        }
        nodeConfigs.add(nodeConfig);

        raftNodeExtConfigMap.put(newNode.getNodeId(), new RaftNodeExtConfig());
        nodeIdRaftNodeMap.put(newNode.getNodeId(), newNode);
        raftNodeStartProperties.put(newNode.getNodeId(), properties);
        raftNodes.add(newNode);
        return ClusterConfCommand.create(nodeConfigs);
    }

    @Override
    public ClusterConfCommand newDelNodeCommand(NodeId nodeId) {
        RaftNode raftNode = nodeIdRaftNodeMap.get(nodeId);
        List<NodeConfig> nodeConfigsCopy = Lists.newArrayList(raftNode.getClusterConfig().getNodeConfigs());
        NodeConfig config = NodeConfig.create(nodeId, raftNode.getEndPoint());
        nodeConfigsCopy.remove(config);
        ClusterConfig clusterConfig = ClusterConfig.create(nodeConfigsCopy);
        return ClusterConfCommand.create(clusterConfig.getNodeConfigs());
    }

    @Override
    public EndPoint fetchLeaderEndPoint() {
        NodeId leaderNodeId = checkOneLeader();
        return nodeIdRaftNodeMap.get(leaderNodeId).getEndPoint();
    }

    @Override
    public void checkClusterConfig() {
        List<ClusterConfig> clusterConfigs = this.raftNodes.stream().map(RaftNode::getClusterConfig).collect(Collectors.toList());
        for (int i = 1; i < clusterConfigs.size(); i++) {
            ClusterConfig clusterConfig1 = clusterConfigs.get(i -1);
            ClusterConfig clusterConfig2 = clusterConfigs.get(i -1);
            if (!clusterConfig1.equals(clusterConfig2)) {
                fail("cluster not equals, cluster1={}, cluster2={}", clusterConfig1, clusterConfig2);
            }
        }
    }

    class RaftNodeExtConfig {

        RaftNodeExtConfig() {
            this.isOnNet = true;
        }

        private boolean isOnNet;

        public boolean isOnNet() {
            return isOnNet;
        }

        public void setOnNet(boolean onNet) {
            isOnNet = onNet;
        }
    }

    /**
     * @see #begin(String)
     */
    private long t0;
    private long rpcs0;
    private long cmds0;
    private long maxIndex0;

    private ClusterManager() {
    }

    private void makeCluster(int servers, boolean unreliable) {
        Preconditions.checkArgument(servers > 0);

        this.servers = servers;
        List<RaftNode> raftNodes = Lists.newArrayList();


        nodeRpcPortAllocator = new AtomicInteger(5283);

        List<String> nodeList = Lists.newArrayList();
        for (int i = 0; i < servers; i++) {
            nodeList.add("localhost:" + nodeRpcPortAllocator.getAndAdd(3) + ":" + i);
        }
        nodeList.forEach(currentNode -> {
            Properties properties = new Properties();
            properties.setProperty("local", currentNode);
            List<String> peerNode = Lists.newLinkedList(nodeList);
            peerNode.remove(currentNode);
            properties.setProperty("peer", Joiner.on(',').join(peerNode));
            RaftNode node = new RaftStarter().start(properties);
            raftNodes.add(node);
            raftNodeExtConfigMap.put(node.getNodeId(), new RaftNodeExtConfig());
            nodeIdRaftNodeMap.put(node.getNodeId(), node);
            raftNodeStartProperties.put(node.getNodeId(), properties);
        });
        this.raftNodes = raftNodes;
        this.reliable = !unreliable;
        setNetReliable(!unreliable);
        this.raftNodes.forEach(RaftNode::init);
    }


    @Override
    public NodeId checkOneLeader() {
        for (int i = 0; i < 10; i++) {
            pause(getRandomStartElectionTimeout() + 200);
            //term -> raftNodes
            Map<Long, List<RaftNode>> leaders = Maps.newHashMap();
            raftNodes.forEach(raftNode -> {
                if (isOnNet(raftNode) && raftNode.getNodeScheduler().isLeader()) {
                    if (Objects.isNull(leaders.get(raftNode.getCurrentTerm()))) {
                        leaders.put(raftNode.getCurrentTerm(), Lists.newLinkedList());
                    }
                    leaders.get(raftNode.getCurrentTerm()).add(raftNode);
                }
            });
            long lastTermWithLeader = -1;
            for (Map.Entry<Long, List<RaftNode>> entry : leaders.entrySet()) {
                if (entry.getValue().size() > 1) {
                    fail("term {} has %d (>1) leaders", entry.getKey(), entry.getValue().size());
                }
                if (entry.getKey() > lastTermWithLeader) {
                    lastTermWithLeader = entry.getKey();
                }
            }
            if (leaders.size() != 0) {
                return leaders.get(lastTermWithLeader).get(0).getNodeId();
            }
        }
        fail("expected one leader, got none");
        return null;
    }

    @Override
    public NodeId nextNode(NodeId nodeId) {
        int peerId = nodeId.getPeerId() + 1;
        return NodeId.create(peerId % servers);
    }

    @Override
    public void setunreliable(boolean unreliable) {
        setNetReliable(!unreliable);
    }

    @Override
    public boolean isOnNet(NodeId nodeId) {
        return raftNodeExtConfigMap.get(nodeId).isOnNet;
    }

    @Override
    public int checkTerms() {
        long term = -1;
        for (RaftNode raftNode : raftNodes) {
            if (isOnNet(raftNode)) {
                if (term == -1) {
                    term = raftNode.getCurrentTerm();
                } else if (term != raftNode.getCurrentTerm()) {
                    fail("servers disagree on term");
                }
            }
        }
        return Math.toIntExact(term);
    }

    @Override
    public void checkNoLeader() {
        for (RaftNode raftNode : raftNodes) {
            if (isOnNet(raftNode) && raftNode.getNodeScheduler().isLeader()) {
                fail("expected no leader, but {} claims to be leader", raftNode.getNodeId());
            }
        }
    }

    @Override
    public Pair<Integer, Command> nCommitted(int index) {
        int count = 0;
        Command defaultCommand = LogConstants.getDummyCommand();

        for (RaftNode raftNode : raftNodes) {
            LogService logService = raftNode.getLogService();
            LogEntry entry = null;
            if (index <= logService.getLastCommitLogIndex()) {
                entry = logService.get(index);
            }
            if (Objects.nonNull(entry)) {
                if (count > 0 && !Objects.equals(defaultCommand, entry.getCommand())) {
                    dumpAllNode();
                    fail("committed values do not match: index {}, {}, NodeId={} = {}", index, defaultCommand, raftNode.getNodeId(), entry.getCommand());
                }
                count++;
                defaultCommand = entry.getCommand();
            }
        }
        return Pair.of(count, defaultCommand);
    }

    @Override
    public int one(Command command, int expectedServers, boolean retry) {
        NodeId leaderNodeId = null;
        long current = System.currentTimeMillis();
        int tryTimes = 0;
        while ((System.currentTimeMillis() - current) < TimeUnit.SECONDS.toMillis(10)) {
            try {
                for (int i = 0; i < fetchAllNodeId().size(); i++) {
                    NodeId nodeId = NodeId.create(i);
                    if (isOnNet(nodeId) && nodeIdRaftNodeMap.get(nodeId).getNodeScheduler().isLeader()) {
                        leaderNodeId = nodeId;
                        break;
                    }
                }
                if (Objects.isNull(leaderNodeId)) {
                    continue;
                }
                RaftNode leaderRaftNode = nodeIdRaftNodeMap.get(leaderNodeId);
                JsonObject jsonObject = leaderRaftNode.getOuterService().appendLog((DefaultCommand) command);
                if (!jsonObject.getBoolean("success")) {
                    tryTimes++;
                    log.info("appendLog fail, leaderNodeId={}, command={}", JSON.toJSONString(leaderNodeId),JSON.toJSONString(command));
                    dumpAllNode();
                    leaderNodeId = null;
                    continue;
                }
                long lastLogIndex = jsonObject.getInteger("index");
                log.info("one lastLogIndex={}, LeaderNodeId={}", lastLogIndex, leaderNodeId);
                int times = retry ? 2 : 1;
                while (times >= 1) {
                    long t0 = System.currentTimeMillis();
                    while (System.currentTimeMillis() - t0 < TimeUnit.SECONDS.toMillis(2)) {
                        Pair<Integer, Command> commandPair = nCommitted(Math.toIntExact(lastLogIndex));
                        if (commandPair.getKey() > 0 && commandPair.getKey() >= expectedServers) {
                            if (Objects.equals(command, commandPair.getValue())) {
                                return Math.toIntExact(lastLogIndex);
                            }
                        }
                    }
                    times--;
                }
                break;
            } catch (Exception e) {
                //ignore
                System.out.println("one exception fail");
                dumpAllNode();
                throw e;
            }
        }
        if (Objects.isNull(leaderNodeId)) {
            log.info("before fail");
            dumpAllNode();
            fail("one({}) failed to reach agreement", command);
        }
        log.info("before fail");
        dumpAllNode();
        fail("one({}) failed to reach agreement, expectedServers={}, lastTryLeaderNodeId={}, tryTimes={}", command, expectedServers, leaderNodeId, tryTimes);
        return -1;
    }

    @Override
    public void begin(String description) {
        log.info("{} ...", description);
        this.t0 = System.currentTimeMillis();
        //TODO
        this.rpcs0 = 0;
        this.cmds0 = 0;
        //TODO
        this.maxIndex0 = 0;
    }

    /**
     * 设置网络的时延
     */
    @Override
    public void setlongreordering(boolean longrel) {
        raftNodes.forEach(raftNode -> {
            List<PeerRaftNode> peerRaftNodes = getObjectMember(raftNode, "peerRaftNodes");
            Preconditions.checkNotNull(peerRaftNodes);
            peerRaftNodes.forEach(peerRaftNode -> {
                RpcClientOptions rpcClientOptions = getObjectMember(peerRaftNode, "rpcClientOptions");
                Preconditions.checkNotNull(rpcClientOptions);
                rpcClientOptions.setLongReordering(longrel);
            });
        });
    }

    @Override
    public void end() {
        if (Objects.nonNull(raftNodes)) {
            raftNodes.forEach(RaftNode::close);
        }
    }

    @Override
    public int rpcTotal() {
        //TODO
        return 0;
    }

    @Override
    public void disconnect(NodeId nodeId) {
        System.out.println("disconnect nodeId=" + nodeId);
        setNetStatus(nodeId, false);
    }

    @Override
    public void connect(NodeId nodeId) {
        System.out.println("connect nodeId=" + nodeId);
        setNetStatus(nodeId, true);
    }

    private void setNetStatus(NodeId nodeId, boolean isOnNet) {
        //当前节点断开与peerNode的网络，当前节点向其他节点发送请求
        raftNodes.stream().filter(raftNode -> raftNode.getNodeId().equals(nodeId)).findAny().ifPresent(currentNode -> {
            List<PeerRaftNode> currentNodePeerRaftNodes = getObjectMember(currentNode, "peerRaftNodes");
            currentNodePeerRaftNodes.forEach(peerRaftNode -> {
                RpcClientOptions rpcClientOptions = getObjectMember(peerRaftNode, "rpcClientOptions");
                Preconditions.checkNotNull(rpcClientOptions);
                rpcClientOptions.setOnNet(isOnNet);
            });

            //当前节点能否回复请求
            RpcServer rpcServer = getObjectMember(currentNode, "rpcServer");
            Preconditions.checkNotNull(rpcServer);
            RpcServerOptions rpcServerOptions = getObjectMember(rpcServer, "rpcServerOptions");
            Preconditions.checkNotNull(rpcServerOptions);
            rpcServerOptions.setOnNet(isOnNet);
        });
        raftNodeExtConfigMap.get(nodeId).setOnNet(isOnNet);
    }

    /**
     * 设置网络的可靠性
     */
    private void setNetReliable(boolean reliable) {
        raftNodes.forEach(raftNode -> {
            List<PeerRaftNode> peerRaftNodes = getObjectMember(raftNode, "peerRaftNodes");
            Preconditions.checkNotNull(peerRaftNodes);
            peerRaftNodes.forEach(peerRaftNode -> {
                RpcClientOptions rpcClientOptions = getObjectMember(peerRaftNode, "rpcClientOptions");
                Preconditions.checkNotNull(rpcClientOptions);
                rpcClientOptions.setReliable(reliable);
            });
        });
    }

    @Override
    public StartResponse start(NodeId nodeId, DefaultCommand command) {
        RaftNode node = nodeIdRaftNodeMap.get(nodeId);
        if (!node.getNodeScheduler().isLeader()) {
            return StartResponse.create(-1, -1, false);
        }
        node.getOuterService().appendLog(command);
        boolean isLeader = node.getNodeScheduler().isLeader();
        int term = Math.toIntExact(node.getCurrentTerm());
        int lastLogIndex = Math.toIntExact(node.getLogService().getLastLogIndex());
        return StartResponse.create(lastLogIndex, term, isLeader);
    }

    @Override
    public Command wait(int index, int n, long startTerm) {
        long to = TimeUnit.MILLISECONDS.toMillis(10);
        for (int i = 0; i < 30; i++) {
            Pair<Integer, Command> integerCommandPair = nCommitted(index);
            if (integerCommandPair.getKey() >= n) {
                break;
            }
            pause(to);
            if (to < TimeUnit.SECONDS.toMillis(1)) {
                to *= 2;
            }
            if (startTerm > -1) {
                for (RaftNode raftNode : raftNodes) {
                    if (raftNode.getCurrentTerm() > startTerm) {
                        return null;
                    }
                }
            }
        }
        Pair<Integer, Command> integerCommandPair = nCommitted(index);
        if (integerCommandPair.getKey() < n) {
            fail("only {} decided for index {}; wanted {}", integerCommandPair.getKey(), index, n);
        }
        return integerCommandPair.getValue();
    }

    @Override
    public int fetchTerm(NodeId nodeId) {
        return Math.toIntExact(nodeIdRaftNodeMap.get(nodeId).getCurrentTerm());
    }

    @Override
    public Collection<NodeId> fetchAllNodeId() {
        return nodeIdRaftNodeMap.keySet();
    }

    @Override
    public int rpcCount(NodeId nodeId) {
        RaftNode raftNode = nodeIdRaftNodeMap.get(nodeId);
        RpcServer rpcServer = getObjectMember(raftNode, "rpcServer");
        RpcStatistics rpcStatistics = getObjectMember(rpcServer, "rpcStatistics");
        return Math.toIntExact(rpcStatistics.fetchIncomingRequestCount());
    }

    @Override
    public void crash1(NodeId nodeId) {
        disconnect(nodeId);
        RaftNode raftNode = nodeIdRaftNodeMap.get(nodeId);
        if (Objects.isNull(raftNode)) {
            //has crash before
        } else {
            Long currentTerm = raftNode.getCurrentTerm();
            NodeId voteFor = raftNode.getNodeScheduler().getVotedFor();
            LogService logService = raftNode.getLogService();
            SavedNodeStatus savedNodeStatus = new SavedNodeStatus();
            savedNodeStatus.setCurrentTerm(currentTerm);
            savedNodeStatus.setLogService(logService);
            savedNodeStatus.setVoteFor(voteFor);
            raftNode.close();
            raftNodeSavedNodeStatus.put(nodeId, savedNodeStatus);
            nodeIdRaftNodeMap.put(nodeId, null);
            raftNodes = nodeIdRaftNodeMap.values().stream().filter(Objects::nonNull).collect(Collectors.toList());
        }
    }

    @Override
    public void start1(NodeId nodeId) {
        crash1(nodeId);
        SavedNodeStatus savedNodeStatus = raftNodeSavedNodeStatus.get(nodeId);
        RaftNode raftNode = new RaftStarter().start(raftNodeStartProperties.get(nodeId));
        raftNode.init1(savedNodeStatus.getLogService(), savedNodeStatus.getCurrentTerm(), savedNodeStatus.getVoteFor());
        nodeIdRaftNodeMap.put(nodeId, raftNode);
        raftNodeExtConfigMap.put(nodeId, new RaftNodeExtConfig());
        raftNodes = Lists.newArrayList(nodeIdRaftNodeMap.values().stream().filter(Objects::nonNull).collect(Collectors.toList()));
        setNetReliable(reliable);
    }

    @Override
    public boolean exist(NodeId nodeId) {
        return nodeIdRaftNodeMap.get(nodeId) != null;
    }

    /**
     * 判断一个节点是否在Net上
     */
    private boolean isOnNet(RaftNode raftNode) {
        return raftNodeExtConfigMap.get(raftNode.getNodeId()).isOnNet();
    }

    private void fail(String errMsg, Object... objects) {
        log.error(errMsg, objects);
        throw new FailException();
    }

    @Override
    public void dumpAllNode() {
        for (RaftNode raftNode : raftNodes) {
            JsonObject jsonObject = new JsonObject();
            jsonObject.put("nodeId", raftNode.getNodeId().toString());
            jsonObject.put("isOnNet", raftNodeExtConfigMap.get(raftNode.getNodeId()).isOnNet);
            jsonObject.put("role", raftNode.getNodeScheduler().isLeader() ? "leader" : "follower");
            jsonObject.put("currentTerm", raftNode.getCurrentTerm());
            jsonObject.put("LogEntrySize", raftNode.getLogService().getStorageService().getLen());
            jsonObject.put("voteFor", raftNode.getNodeScheduler().getVotedFor() == null ? "null" : raftNode.getNodeScheduler().getVotedFor().toString());
            jsonObject.put("LogEntry", raftNode.getLogService().getStateMachine().fetchAllEntry());
            log.info(jsonObject.toString());
        }
    }

    private void pause(long mills) {
        try {
            Thread.sleep(mills);
        } catch (InterruptedException e) {
            log.error(e.getMessage(), e);
        }
    }
}
