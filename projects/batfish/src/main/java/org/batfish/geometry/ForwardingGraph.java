package org.batfish.geometry;

import com.google.common.base.Objects;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.batfish.common.BatfishException;
import org.batfish.datamodel.BackendType;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.DataPlane;
import org.batfish.datamodel.Edge;
import org.batfish.datamodel.FilterResult;
import org.batfish.datamodel.Flow;
import org.batfish.datamodel.FlowDisposition;
import org.batfish.datamodel.FlowHistory;
import org.batfish.datamodel.FlowTrace;
import org.batfish.datamodel.FlowTraceHop;
import org.batfish.datamodel.ForwardingAction;
import org.batfish.datamodel.HeaderSpace;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.IpAccessList;
import org.batfish.datamodel.IpAccessListLine;
import org.batfish.datamodel.LineAction;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.TcpFlags;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.datamodel.collections.FibRow;
import org.batfish.datamodel.collections.NodeInterfacePair;
import org.batfish.datamodel.pojo.Environment;
import org.batfish.main.Batfish;
import org.batfish.symbolic.utils.Tuple;

/*
 * An edge-labelled graph that captures the forwarding behavior of
 * all packets. Packets are slice into equivalence classes that get
 * refined as new forwarding and ACL rules get added to the data structure.
 *
 * <p>Nodes are split into two categories: routers and ACL nodes. ACL nodes are
 * treated as special to make it easier to determine the cause of a packet drop.
 * It is also convenient to deal with ACLs in a uniform way, ACL entries are just
 * forwarding rules that either forward to a special "drop" node, or out the interface
 * to the neighbor.</p>
 *
 * <p>There is a special drop node that all routers can forward to, for example,
 * if they have a null route</p>
 *
 * <p>The equivalence classes are represented by multidimensional hyperrectangles.
 * When a new rule is added, we find all overlapping rectangles and refine the space
 * by splitting into more rules. Updating the edge-labelled graph is done in the
 * same was as with delta-net.</p>
 *
 *
 * List of possible further optimizations:
 *
 * - Use a persistent map for _owner to avoid all the deep copies and
 *   reduce space consumption.
 *
 * - There are better datastructures than KD trees for collision detection.
 *   Are any easy to implement?
 *
 */
public class ForwardingGraph {

  private static int ACCEPT_FLAG = 0;
  private static int DROP_FLAG = 1;
  private static int DROP_ACL_FLAG = 2;
  private static int DROP_ACL_IN_FLAG = 3;
  private static int DROP_ACL_OUT_FLAG = 4;
  private static int DROP_NULL_ROUTE_FLAG = 5;
  private static int DROP_NO_ROUTE_FLAG = 6;

  // Factory for creating shapes
  private GeometricSpaceFactory _factory;

  // Equivalence classes indexed from 0
  private ArrayList<HyperRectangle> _ecs;

  // Edges labelled with equivalence classes, indexed by link id
  private BitSet[] _labels;

  // EC index to graph node index to set of rules for that EC on that node.
  private ArrayList<Map<GraphNode, Rule>> _ownerMap;

  // Efficient searching for equivalence class overlap
  private KDTree _kdtree;

  // All the nodes in the graph
  private List<GraphNode> _allNodes;

  // All the links in the graph
  private List<GraphLink> _allLinks;

  // Adjacency list for the graph indexed by GraphNode index
  private ArrayList<List<GraphLink>> _adjacencyLists;

  // Map from routers to graph nodes in this extended graph
  private Map<String, GraphNode> _nodeMap;

  // Map from ACLs to graph nodes in this extended graph
  private Map<String, AclGraphNode> _aclMap;

  // Map from interfaces to links in this extened graph
  private Map<NodeInterfacePair, GraphLink> _linkMap;

  // A dag used to represent difference of cubes dependencies
  private ArrayList<Set<Integer>> _dag;

  // Store the current volume for each EC when using DoC
  private ArrayList<BigInteger> _volumes;

  // Which backend to use
  private BackendType _backendType;

  // Reference to the Batfish object so we can access the ACLs in the configs
  private Batfish _batfish;

  @SuppressWarnings("unused")
  private long _time = 0L;

  /*
   * Construct the edge-labelled graph from the configurations
   * and the dataplane generated by Batfish
   */
  public ForwardingGraph(Batfish batfish, DataPlane dp, BackendType backendType) {
    long t = System.currentTimeMillis();
    _batfish = batfish;
    _backendType = backendType;

    initGraph(batfish, dp);

    // only model fields that are used in the configs
    EnumSet<PacketField> fields = findRelevantPacketFields(batfish);
    _factory = new GeometricSpaceFactory(fields);

    HyperRectangle fullRange = _factory.fullSpace();
    fullRange.setAlphaIndex(0);
    _ecs = new ArrayList<>();
    _ecs.add(fullRange);
    _ownerMap = new ArrayList<>();
    _kdtree = new KDTree(_factory.numFields());
    _kdtree.insert(fullRange);
    _dag = new ArrayList<>();
    _dag.add(new HashSet<>());
    _volumes = new ArrayList<>();
    _volumes.add(fullRange.volume());

    _ownerMap.add(new HashMap<>());

    // initialize the labels
    _labels = new BitSet[_allLinks.size()];
    for (GraphLink link : _allLinks) {
      _labels[link.getIndex()] = new BitSet();
    }

    // add the FIB rules
    List<Rule> rules = new ArrayList<>();
    for (Entry<String, Map<String, SortedSet<FibRow>>> entry : dp.getFibs().entrySet()) {
      String router = entry.getKey();
      for (Entry<String, SortedSet<FibRow>> entry2 : entry.getValue().entrySet()) {
        SortedSet<FibRow> fibs = entry2.getValue();
        for (FibRow fib : fibs) {
          Rule r = createFibRule(router, fib);
          rules.add(r);
        }
      }
    }

    // add the ACL rules
    List<Rule> aclRules = new ArrayList<>();
    for (AclGraphNode aclNode : _aclMap.values()) {
      List<GraphLink> links = _adjacencyLists.get(aclNode.getIndex());
      GraphLink drop = links.get(0);
      GraphLink accept = links.get(1);
      List<IpAccessListLine> lines = aclNode.getAcl().getLines();
      int i = lines.size();
      for (IpAccessListLine aclLine : aclNode.getAcl().getLines()) {
        Rule r = createAclRule(aclLine, drop, accept, i);
        aclRules.add(r);
        i--;
      }
      // default drop rule
      Rule r = createAclRule(null, drop, accept, 0);
      rules.add(r);
    }

    // Sort the rules to ensure a deterministic order since they were stored in a hashmap
    rules.sort(Comparator.comparing(Rule::getRectangle));
    // Deterministically shuffle the input to get a better balanced KD tree
    Random rand = new Random(7);
    Collections.shuffle(rules, rand);
    // Adding acl rules first gives a better splitting of the KD tree
    aclRules.addAll(rules);
    rules = aclRules;

    for (Rule rule : rules) {
      if (backendType == BackendType.DELTANET) {
        addRule(rule);
      } else if (backendType == BackendType.DELTANET_DOC) {
        addRuleDoc(rule);
      } else {
        throw new BatfishException("Invalid backed type: " + backendType);
      }
    }

    System.out.println("Number of rules: " + rules.size());
    System.out.println("Time to build labelled graph: " + (System.currentTimeMillis() - t));
    System.out.println("Number of classes: " + (_ecs.size()));

    // System.out.println("Time for function: " + _time);
    // showStatus();
  }

  /*
   * Finds what packet fields are actually matched somewhere in the configurations
   */
  private EnumSet<PacketField> findRelevantPacketFields(Batfish batfish) {
    EnumSet<PacketField> fields = EnumSet.noneOf(PacketField.class);
    fields.add(PacketField.DSTIP);
    Map<String, Configuration> configs = batfish.loadConfigurations();
    for (Configuration config : configs.values()) {
      for (Interface iface : config.getInterfaces().values()) {
        if (iface.getOutgoingFilter() != null) {
          addFields(iface.getOutgoingFilter(), fields);
        }
        if (iface.getIncomingFilter() != null) {
          addFields(iface.getIncomingFilter(), fields);
        }
      }
    }
    return fields;
  }

  private void addFields(IpAccessList acl, EnumSet<PacketField> fields) {
    for (IpAccessListLine aclLine : acl.getLines()) {
      if (!aclLine.getSrcIps().isEmpty()) {
        fields.add(PacketField.SRCIP);
      }
      if (!aclLine.getDstPorts().isEmpty()) {
        fields.add(PacketField.DSTPORT);
      }
      if (!aclLine.getSrcPorts().isEmpty()) {
        fields.add(PacketField.SRCPORT);
      }
      if (!aclLine.getIpProtocols().isEmpty()) {
        fields.add(PacketField.IPPROTO);
      }
      if (!aclLine.getIcmpTypes().isEmpty()) {
        fields.add(PacketField.ICMPTYPE);
      }
      if (!aclLine.getIcmpCodes().isEmpty()) {
        fields.add(PacketField.ICMPCODE);
      }
      if (!aclLine.getTcpFlags().isEmpty()) {
        fields.add(PacketField.TCPACK);
        fields.add(PacketField.TCPCWR);
        fields.add(PacketField.TCPECE);
        fields.add(PacketField.TCPFIN);
        fields.add(PacketField.TCPPSH);
        fields.add(PacketField.TCPRST);
        fields.add(PacketField.TCPSYN);
        fields.add(PacketField.TCPURG);
      }
    }
  }

  /*
   * Initialize the edge-labelled graph by creating nodes for
   * every router, and special ACL nodes for every ACL.
   */
  private void initGraph(Batfish batfish, DataPlane dp) {
    _nodeMap = new HashMap<>();
    _aclMap = new HashMap<>();
    _linkMap = new HashMap<>();
    _allNodes = new ArrayList<>();
    _allLinks = new ArrayList<>();

    Map<String, Configuration> configs = batfish.loadConfigurations();

    // Create the nodes
    GraphNode dropNode = new GraphNode("(none)", 0);
    _nodeMap.put("(none)", dropNode);
    _allNodes.add(dropNode);

    int nodeIndex = 1;
    for (Entry<String, Configuration> entry : configs.entrySet()) {
      String router = entry.getKey();
      Configuration config = entry.getValue();
      GraphNode node = new GraphNode(router, nodeIndex);
      nodeIndex++;
      _nodeMap.put(router, node);
      _allNodes.add(node);
      // Create ACL nodes
      for (Entry<String, Interface> e : config.getInterfaces().entrySet()) {
        String ifaceName = e.getKey();
        Interface iface = e.getValue();
        IpAccessList outAcl = iface.getOutgoingFilter();
        if (outAcl != null) {
          String aclName = getAclName(router, ifaceName, outAcl, false);
          AclGraphNode aclNode = new AclGraphNode(aclName, nodeIndex, outAcl, node);
          nodeIndex++;
          _aclMap.put(aclName, aclNode);
          _allNodes.add(aclNode);
        }
        IpAccessList inAcl = iface.getIncomingFilter();
        if (inAcl != null) {
          String aclName = getAclName(router, ifaceName, inAcl, true);
          AclGraphNode aclNode = new AclGraphNode(aclName, nodeIndex, inAcl, node);
          nodeIndex++;
          _aclMap.put(aclName, aclNode);
          _allNodes.add(aclNode);
        }
      }
    }

    // Initialize the node adjacencies
    _adjacencyLists = new ArrayList<>(_allNodes.size());
    for (int i = 0; i < _allNodes.size(); i++) {
      _adjacencyLists.add(null);
    }
    for (GraphNode node : _nodeMap.values()) {
      _adjacencyLists.set(node.getIndex(), new ArrayList<>());
    }
    for (GraphNode node : _aclMap.values()) {
      _adjacencyLists.set(node.getIndex(), new ArrayList<>());
    }

    Map<NodeInterfacePair, NodeInterfacePair> edgeMap = new HashMap<>();
    for (Edge edge : dp.getTopologyEdges()) {
      edgeMap.put(edge.getInterface1(), edge.getInterface2());
    }

    // add edges that don't have a neighbor on the other side
    NodeInterfacePair nullPair = new NodeInterfacePair("(none)", "null_interface");
    for (Entry<String, Configuration> entry : configs.entrySet()) {
      String router = entry.getKey();
      Configuration config = entry.getValue();

      // Add a null interface to the drop node for every router
      NodeInterfacePair nip = new NodeInterfacePair(router, "null_interface");
      edgeMap.put(nip, nullPair);

      for (Entry<String, Interface> e : config.getInterfaces().entrySet()) {
        nip = new NodeInterfacePair(router, e.getKey());
        if (!edgeMap.containsKey(nip)) {
          edgeMap.put(nip, nullPair);
        }
      }
    }

    int linkIndex = 0;

    // Create the edges
    /* for (GraphNode aclNode : _aclMap.values()) {
      GraphLink nullLink =
          new GraphLink(aclNode, "null_interface", dropNode, "null_interface", linkIndex);
      linkIndex++;
      _adjacencyLists.get(aclNode.getIndex()).add(nullLink);
      _allLinks.add(nullLink);
    } */

    for (Entry<NodeInterfacePair, NodeInterfacePair> entry : edgeMap.entrySet()) {
      NodeInterfacePair nip1 = entry.getKey();
      NodeInterfacePair nip2 = entry.getValue();

      GraphNode src = _nodeMap.get(nip1.getHostname());

      // Add a special null edge
      /* GraphLink nullLink =
          new GraphLink(src, "null_interface", dropNode, "null_interface", linkIndex);
      linkIndex++;
      _linkMap.put(new NodeInterfacePair(nip1.getHostname(), "null_interface"), nullLink);
      _allLinks.add(nullLink); */

      String router1 = nip1.getHostname();
      String router2 = nip2.getHostname();
      Configuration config1 = configs.get(router1);
      Configuration config2 = configs.get(router2);
      String ifaceName1 = nip1.getInterface();
      String ifaceName2 = nip2.getInterface();

      if (ifaceName1.equals("null_interface")) {
        GraphLink l = new GraphLink(src, ifaceName1, dropNode, "null_interface", linkIndex);
        linkIndex++;
        _linkMap.put(nip1, l);
        _adjacencyLists.get(src.getIndex()).add(l);
        _allLinks.add(l);
        continue;
      }

      Interface iface1 = config1.getInterfaces().get(ifaceName1);
      Interface iface2 = config2 == null ? null : config2.getInterfaces().get(ifaceName2);
      IpAccessList outAcl = iface1.getOutgoingFilter();
      IpAccessList inAcl = iface2 == null ? null : iface2.getIncomingFilter();

      if (outAcl != null) {
        // add a link to the ACL
        String outAclName = getAclName(router1, ifaceName1, outAcl, false);
        AclGraphNode tgt1 = _aclMap.get(outAclName);
        GraphLink l1 = new GraphLink(src, ifaceName1, tgt1, "enter-outbound-acl", linkIndex);
        tgt1.setOwnerLink(l1);
        linkIndex++;
        _linkMap.put(nip1, l1);
        _adjacencyLists.get(src.getIndex()).add(l1);
        _allLinks.add(l1);
        // if inbound acl, then add that
        if (inAcl != null) {
          String inAclName = getAclName(router2, ifaceName2, inAcl, true);
          AclGraphNode tgt2 = _aclMap.get(inAclName);
          GraphLink l2 =
              new GraphLink(tgt1, "exit-outbound-acl", tgt2, "enter-inbound-acl", linkIndex);
          tgt2.setOwnerLink(l2);
          linkIndex++;
          _adjacencyLists.get(tgt1.getIndex()).add(l2);
          _allLinks.add(l2);
          // add a link from ACL to peer
          GraphNode tgt3 = _nodeMap.get(router2);
          GraphLink l3 = new GraphLink(tgt2, "exit-inbound-acl", tgt3, ifaceName2, linkIndex);
          linkIndex++;
          _adjacencyLists.get(tgt2.getIndex()).add(l3);
          _allLinks.add(l3);
        } else {
          // add a link from ACL to peer
          GraphNode tgt2 = _nodeMap.get(router2);
          GraphLink l2 = new GraphLink(tgt1, "exit-outbound-acl", tgt2, ifaceName2, linkIndex);
          linkIndex++;
          _adjacencyLists.get(tgt1.getIndex()).add(l2);
          _allLinks.add(l2);
        }
      } else {
        if (inAcl != null) {
          String inAclName = getAclName(router2, ifaceName2, inAcl, true);
          AclGraphNode tgt1 = _aclMap.get(inAclName);
          GraphLink l1 = new GraphLink(src, ifaceName1, tgt1, "enter-inbound-acl", linkIndex);
          tgt1.setOwnerLink(l1);
          linkIndex++;
          _linkMap.put(nip1, l1);
          _adjacencyLists.get(src.getIndex()).add(l1);
          _allLinks.add(l1);
          // add a link from ACL to peer
          GraphNode tgt2 = _nodeMap.get(router2);
          GraphLink l2 = new GraphLink(tgt1, "exit-inbound-acl", tgt2, ifaceName2, linkIndex);
          linkIndex++;
          _adjacencyLists.get(tgt1.getIndex()).add(l2);
          _allLinks.add(l2);
        } else {
          GraphNode tgt = _nodeMap.get(router2);
          GraphLink l = new GraphLink(src, ifaceName1, tgt, ifaceName2, linkIndex);
          linkIndex++;
          _linkMap.put(nip1, l);
          _adjacencyLists.get(src.getIndex()).add(l);
          _allLinks.add(l);
        }
      }
    }
  }

  /*
   * Create a Rule from a FIB entry. The link corresponds to the
   * FIB next hop, and the priority is just the prefix length.
   */
  private Rule createFibRule(String router, FibRow fib) {
    NodeInterfacePair nip = new NodeInterfacePair(router, fib.getInterface());
    GraphLink link = _linkMap.get(nip);
    Prefix p = fib.getPrefix();
    long start = p.getStartIp().asLong();
    long end = p.getEndIp().asLong() + 1;
    HyperRectangle hr = _factory.fullSpace();
    hr.getBounds()[0] = start;
    hr.getBounds()[1] = end;
    return new Rule(link, hr, fib.getPrefix().getPrefixLength());
  }

  /*
   * Create a rule from an ACL line. The link is either to the drop
   * node or to the neighbor. The priority the inverse of the line number
   */
  private Rule createAclRule(
      @Nullable IpAccessListLine aclLine, GraphLink drop, GraphLink accept, int priority) {
    if (aclLine == null) {
      HyperRectangle rect = _factory.fullSpace();
      return new Rule(drop, rect, priority);
    } else {
      GeometricSpace space = _factory.fromAcl(aclLine);
      GraphLink link = (aclLine.getAction() == LineAction.ACCEPT ? accept : drop);
      return new Rule(link, space.rectangles().get(0), priority);
    }
  }

  /*
   * Ensure that we make ACL names unique to avoid conflicts
   * when mapping from the concrete name to the ACL's node.
   */
  private String getAclName(String router, String ifaceName, IpAccessList acl, boolean in) {
    return "ACL-" + (in ? "IN-" : "OUT-") + router + "-" + ifaceName + "-" + acl.getName();
  }

  @SuppressWarnings("unused")
  private void showStatus() {
    System.out.println("=====================");
    for (int i = 0; i < _ecs.size(); i++) {
      HyperRectangle r = _ecs.get(i);
      System.out.println(i + " --> " + r);
    }
    System.out.println("=====================");
  }

  /*
   * Add a rule to the edge-labelled graph by first refining
   * the equivalence classes, finding the relevant overlap,
   * and updating the edge labels accordingly.
   */
  private void addRule(Rule r) {
    HyperRectangle hr = r.getRectangle();
    List<HyperRectangle> overlapping = new ArrayList<>();
    List<Tuple<HyperRectangle, HyperRectangle>> delta = new ArrayList<>();
    for (HyperRectangle other : _kdtree.intersect(hr)) {
      HyperRectangle overlap = hr.overlap(other);
      assert (overlap != null);
      Collection<HyperRectangle> newRects = other.subtract(overlap);
      if (newRects == null) {
        overlapping.add(other);
      } else {
        _kdtree.delete(other);
        boolean first = true;
        for (HyperRectangle rect : newRects) {
          if (first && !rect.equals(other)) {
            other.setBounds(rect.getBounds());
            first = false;
            rect = other;
          } else {
            rect.setAlphaIndex(_ecs.size());
            _ecs.add(rect);
            _ownerMap.add(null);
            delta.add(new Tuple<>(other, rect));
          }
          _kdtree.insert(rect);
          if (rect.equals(overlap)) {
            overlapping.add(rect);
          }
        }
      }
    }

    updateRules(r, overlapping, delta);
  }

  /*
   * An alternative representation of ECs, which is similar to
   * the difference of cubes representation.
   */
  private void addRuleDoc(Rule r) {
    HyperRectangle hr = r.getRectangle();
    List<HyperRectangle> overlapping = new ArrayList<>();
    List<Tuple<HyperRectangle, HyperRectangle>> delta = new ArrayList<>();
    Map<Integer, Tuple<BigInteger, Integer>> cache = new HashMap<>();
    List<HyperRectangle> others = _kdtree.intersect(hr);
    for (HyperRectangle other : others) {
      addRuleDocRec(hr, other, others, cache, overlapping, delta);
    }
    updateRules(r, overlapping, delta);
  }

  private Tuple<BigInteger, Integer> addRuleDocRec(
      HyperRectangle added,
      HyperRectangle other,
      List<HyperRectangle> others,
      Map<Integer, Tuple<BigInteger, Integer>> cache,
      List<HyperRectangle> overlapping,
      List<Tuple<HyperRectangle, HyperRectangle>> delta) {

    Tuple<BigInteger, Integer> cachedValue = cache.get(other.getAlphaIndex());
    if (cachedValue != null) {
      return cachedValue;
    }

    HyperRectangle overlap = added.overlap(other);
    assert (overlap != null);
    BigInteger overlapVolume = overlap.volume();

    if (other.equals(overlap)) {
      overlapping.add(other);
      Tuple<BigInteger, Integer> ret = new Tuple<>(overlapVolume, other.getAlphaIndex());
      cache.put(other.getAlphaIndex(), ret);
      return ret;
    }

    BigInteger childrenVolume = BigInteger.ZERO;
    List<Integer> ecs = new ArrayList<>();

    Set<Integer> childIndices = _dag.get(other.getAlphaIndex());
    for (HyperRectangle o : others) {
      if (childIndices.contains(o.getAlphaIndex())) {
        HyperRectangle child = _ecs.get(o.getAlphaIndex());
        Tuple<BigInteger, Integer> tup =
            addRuleDocRec(added, child, others, cache, overlapping, delta);
        BigInteger vol = tup.getFirst();
        Integer ec = tup.getSecond();
        childrenVolume = childrenVolume.add(vol);
        if (ec != null) {
          ecs.add(ec);
        }
      }
    }

    BigInteger volume = overlapVolume.subtract(childrenVolume);

    if (volume.compareTo(BigInteger.ZERO) > 0) {
      BigInteger otherVolume = _volumes.get(other.getAlphaIndex());
      BigInteger newOtherVolume = otherVolume.subtract(volume);
      // No new region to create if we cover the old region
      if (newOtherVolume.compareTo(BigInteger.ZERO) == 0) {
        overlapping.add(other);
        Tuple<BigInteger, Integer> ret = new Tuple<>(overlapVolume, other.getAlphaIndex());
        cache.put(other.getAlphaIndex(), ret);
        return ret;
      } else {
        _volumes.set(other.getAlphaIndex(), newOtherVolume);
        overlap.setAlphaIndex(_ecs.size());
        _volumes.add(volume);
        _ecs.add(overlap);
        // make sure they are the right size
        _ownerMap.add(null);
        _dag.add(null);
        overlapping.add(overlap);
        _kdtree.insert(overlap);
        Set<Integer> subsumes = new HashSet<>();
        _dag.set(overlap.getAlphaIndex(), subsumes);
        _dag.get(other.getAlphaIndex()).add(overlap.getAlphaIndex());
        delta.add(new Tuple<>(other, overlap));
        subsumes.addAll(ecs);
      }

      Tuple<BigInteger, Integer> ret = new Tuple<>(overlapVolume, overlap.getAlphaIndex());
      cache.put(other.getAlphaIndex(), ret);
      return ret;
    }

    Tuple<BigInteger, Integer> ret = new Tuple<>(overlapVolume, null);
    cache.put(other.getAlphaIndex(), ret);
    return ret;
  }

  /*
   * Given a collection of new ECs and a set of overlapping ECs,
   * and a Rule, it updates the edge labelled graph accordingly.
   */
  private void updateRules(
      Rule r, List<HyperRectangle> overlapping, List<Tuple<HyperRectangle, HyperRectangle>> delta) {

    // Update new rules
    for (Tuple<HyperRectangle, HyperRectangle> d : delta) {
      HyperRectangle alpha = d.getFirst();
      HyperRectangle alphaPrime = d.getSecond();
      Map<GraphNode, Rule> existing = _ownerMap.get(alpha.getAlphaIndex());
      _ownerMap.set(alphaPrime.getAlphaIndex(), new HashMap<>(existing));
      for (Entry<GraphNode, Rule> entry : existing.entrySet()) {
        Rule rule = entry.getValue();
        if (rule != null) {
          GraphLink link = rule.getLink();
          _labels[link.getIndex()].set(alphaPrime.getAlphaIndex());
        }
      }
    }
    // Update old, overlapping rules
    for (HyperRectangle alpha : overlapping) {
      Rule rPrime = null;
      Map<GraphNode, Rule> map = _ownerMap.get(alpha.getAlphaIndex());
      GraphNode source = r.getLink().getSource();
      Rule rule = map.get(source);
      if (rule != null) {
        rPrime = rule;
      }
      boolean ruleUpdate = (rPrime == null || rPrime.compareTo(r) < 0);
      if (ruleUpdate) {
        _labels[r.getLink().getIndex()].set(alpha.getAlphaIndex());
        if (rPrime != null && !(Objects.equal(r.getLink(), rPrime.getLink()))) {
          _labels[rPrime.getLink().getIndex()].clear(alpha.getAlphaIndex());
        }
        _ownerMap.get(alpha.getAlphaIndex()).put(source, r);
      }
    }
  }

  /*
   * Return an example of a flow satisfying the user's query.
   * This will be the standard FlowHistory object for reachability.
   * Finds all relevant equivalence classes and checks reachability on
   * them each in turn.
   */
  public AnswerElement reachable(
      HeaderSpace h, Set<ForwardingAction> actions, Set<String> src, Set<String> dst) {

    long l = System.currentTimeMillis();

    Set<GraphNode> sources = new HashSet<>();
    Set<GraphNode> sinks = new HashSet<>();
    for (String s : src) {
      sources.add(_nodeMap.get(s));
    }
    for (String d : dst) {
      sinks.add(_nodeMap.get(d));
    }

    BitSet flags = actionFlags(actions);

    Map<Integer, HyperRectangle> relevant;
    if (_backendType == BackendType.DELTANET) {
      relevant = findRelevantEcs(h);
    } else if (_backendType == BackendType.DELTANET_DOC) {
      relevant = findRelevantEcsDoc(h);
    } else {
      throw new BatfishException("Invalid backend type: " + _backendType);
    }

    // Check each equivalence class for reachability
    for (Entry<Integer, HyperRectangle> entry : relevant.entrySet()) {
      Integer equivClass = entry.getKey();
      HyperRectangle overlap = entry.getValue();
      Tuple<Path, FlowDisposition> tup = reachable(equivClass, flags, sources, sinks);
      if (tup != null) {
        System.out.println("Reachability time: " + (System.currentTimeMillis() - l));
        return createReachabilityAnswer(_factory.example(overlap), tup);
      }
    }
    System.out.println("Reachability time: " + (System.currentTimeMillis() - l));
    return new FlowHistory();
  }

  /*
   * Convert a set of forwarding actions to a bitset, so that
   * we can check if flags exist more quickly when traversing
   * the forwarding graph.
   */
  private BitSet actionFlags(Set<ForwardingAction> actions) {
    BitSet actionFlags = new BitSet();
    boolean accept = actions.contains(ForwardingAction.ACCEPT);
    boolean drop = actions.contains(ForwardingAction.DROP);
    boolean dropAclIn = actions.contains(ForwardingAction.DROP_ACL_IN);
    boolean dropAclOut = actions.contains(ForwardingAction.DROP_ACL_OUT);
    boolean dropAcl = actions.contains(ForwardingAction.DROP_ACL);
    boolean dropNullRoute = actions.contains(ForwardingAction.DROP_NULL_ROUTE);
    boolean dropNoRoute = actions.contains(ForwardingAction.DROP_NO_ROUTE);

    if (accept) {
      actionFlags.set(ACCEPT_FLAG);
    }
    if (drop) {
      actionFlags.set(DROP_FLAG);
    }
    if (dropAcl) {
      actionFlags.set(DROP_ACL_FLAG);
    }
    if (dropAclIn) {
      actionFlags.set(DROP_ACL_IN_FLAG);
    }
    if (dropAclOut) {
      actionFlags.set(DROP_ACL_OUT_FLAG);
    }
    if (dropNullRoute) {
      actionFlags.set(DROP_NULL_ROUTE_FLAG);
    }
    if (dropNoRoute) {
      actionFlags.set(DROP_NO_ROUTE_FLAG);
    }

    return actionFlags;
  }

  /*
   * Given a query headerspace, pick out the relevant ECs to check
   * and return the overlapping region for each so we can construct
   * an example easily.
   */
  @Nonnull
  private Map<Integer, HyperRectangle> findRelevantEcs(HeaderSpace h) {
    // Pick out the relevant equivalence classes
    GeometricSpace space = _factory.fromHeaderSpace(h);
    Map<Integer, HyperRectangle> relevant = new HashMap<>();
    for (HyperRectangle rect : space.rectangles()) {
      List<HyperRectangle> intersecting = _kdtree.intersect(rect);
      for (HyperRectangle r : intersecting) {
        HyperRectangle overlap = rect.overlap(r);
        relevant.put(r.getAlphaIndex(), overlap);
      }
    }
    return relevant;
  }

  /*
   * Given a query headerspace, pick out the relevant ECs to check
   * and return the overlapping region for each so we can construct
   * an example easily.
   */
  @Nonnull
  private Map<Integer, HyperRectangle> findRelevantEcsDoc(HeaderSpace h) {
    // Pick out the relevant equivalence classes
    GeometricSpace space = _factory.fromHeaderSpace(h);
    Map<Integer, HyperRectangle> relevant = new HashMap<>();
    for (HyperRectangle rect : space.rectangles()) {
      List<HyperRectangle> intersecting = _kdtree.intersect(rect);

      // now we need to check if it actually is relevant, or not
      Map<Integer, BigInteger> cache = new HashMap<>();
      for (HyperRectangle r : intersecting) {
        HyperRectangle overlap = rect.overlap(r);
        assert (overlap != null);
        // Check if this is a relevant EC
        BigInteger overlapVolume = findRelevantEcsDocRec(cache, r, overlap);
        if (overlapVolume.compareTo(BigInteger.ZERO) > 0) {
          relevant.put(r.getAlphaIndex(), overlap);
        }
      }
    }
    return relevant;
  }

  private BigInteger findRelevantEcsDocRec(
      Map<Integer, BigInteger> cache, HyperRectangle r, HyperRectangle overlap) {
    BigInteger vol = cache.get(r.getAlphaIndex());
    if (vol != null) {
      return vol;
    }
    BigInteger childrenVolume = BigInteger.ZERO;
    for (Integer childEc : _dag.get(r.getAlphaIndex())) {
      HyperRectangle child = _ecs.get(childEc);
      HyperRectangle co = child.overlap(overlap);
      if (co != null) {
        childrenVolume = childrenVolume.add(findRelevantEcsDocRec(cache, child, co));
      }
    }
    vol = overlap.volume().subtract(childrenVolume);
    cache.put(r.getAlphaIndex(), vol);
    return vol;
  }

  /*
   * Create a reachability answer element for compatibility
   * with the standard Batfish reachability question.
   */
  private AnswerElement createReachabilityAnswer(HeaderSpace h, Tuple<Path, FlowDisposition> tup) {
    FlowHistory fh = new FlowHistory();

    Flow.Builder b = new Flow.Builder();
    b.setIngressNode(tup.getFirst().getSource().getName());
    if (!h.getSrcIps().isEmpty()) {
      b.setSrcIp(h.getSrcIps().first().getIp());
    }
    if (!h.getDstIps().isEmpty()) {
      b.setDstIp(h.getDstIps().first().getIp());
    }
    if (!h.getSrcPorts().isEmpty()) {
      b.setSrcPort(h.getSrcPorts().first().getStart());
    }
    if (!h.getDstPorts().isEmpty()) {
      b.setDstPort(h.getDstPorts().first().getStart());
    }
    if (!h.getIpProtocols().isEmpty()) {
      b.setIpProtocol(h.getIpProtocols().first());
    }
    if (!h.getIcmpTypes().isEmpty()) {
      b.setIcmpType(h.getIcmpTypes().first().getStart());
    }
    if (!h.getIcmpCodes().isEmpty()) {
      b.setIcmpCode(h.getIcmpCodes().first().getStart());
    }

    if (!h.getTcpFlags().isEmpty()) {
      TcpFlags flags = h.getTcpFlags().get(0);
      int tcpCwr = flags.getCwr() ? 1 : 0;
      int tcpEce = flags.getEce() ? 1 : 0;
      int tcpUrg = flags.getUrg() ? 1 : 0;
      int tcpAck = flags.getAck() ? 1 : 0;
      int tcpPsh = flags.getPsh() ? 1 : 0;
      int tcpRst = flags.getRst() ? 1 : 0;
      int tcpSyn = flags.getSyn() ? 1 : 0;
      int tcpFin = flags.getFin() ? 1 : 0;
      b.setTcpFlagsCwr(tcpCwr);
      b.setTcpFlagsEce(tcpEce);
      b.setTcpFlagsUrg(tcpUrg);
      b.setTcpFlagsAck(tcpAck);
      b.setTcpFlagsPsh(tcpPsh);
      b.setTcpFlagsRst(tcpRst);
      b.setTcpFlagsSyn(tcpSyn);
      b.setTcpFlagsFin(tcpFin);
    }

    b.setTag("DELTANET");

    Flow flow = b.build();

    String testRigName = _batfish.getTestrigName();
    Environment environment =
        new Environment(
            "BASE", testRigName, new TreeSet<>(), null, null, null, null, new TreeSet<>());

    String note = "";
    Path path = tup.getFirst();
    FlowDisposition fd = tup.getSecond();
    if (fd == FlowDisposition.NO_ROUTE) {
      note = "NO_ROUTE";
    }
    if (fd == FlowDisposition.NULL_ROUTED) {
      note = "NULL_ROUTED";
    }
    if (fd == FlowDisposition.ACCEPTED) {
      note = "ACCEPTED";
    }
    if (fd == FlowDisposition.DENIED_OUT || fd == FlowDisposition.DENIED_IN) {
      AclGraphNode aclNode = (AclGraphNode) path.getDestination();
      IpAccessList acl = aclNode.getAcl();
      FilterResult fr = acl.filter(flow);
      String line = "default deny";
      if (fr.getMatchLine() != null) {
        line = acl.getLines().get(fr.getMatchLine()).getName();
      }
      String type = (fd == FlowDisposition.DENIED_OUT) ? "OUT" : "IN";
      note = String.format("DENIED_%s{%s}{%s}", type, acl.getName(), line);
    }

    List<FlowTraceHop> hops = new ArrayList<>();
    for (GraphLink link : path) {
      GraphNode src = link.getSource();
      GraphNode tgt = link.getTarget();
      Edge edge =
          new Edge(src.getName(), link.getSourceIface(), tgt.getName(), link.getTargetIface());
      FlowTraceHop hop = new FlowTraceHop(edge, new TreeSet<>(), null);
      hops.add(hop);
    }

    FlowTrace flowTrace = new FlowTrace(fd, hops, note);
    fh.addFlowTrace(flow, "BASE", environment, flowTrace);
    return fh;
  }

  /*
   * From a BFS search, reconstruct the actual path used to
   * get to the destination node.
   */
  private Path reconstructPath(GraphLink[] predecessors, GraphNode dst) {
    List<GraphLink> list = new ArrayList<>();
    GraphNode current = dst;
    GraphLink prev = predecessors[dst.getIndex()];
    while (prev != null) {
      current = prev.getSource();
      list.add(prev);
      prev = predecessors[current.getIndex()];
    }
    return new Path(list, current, dst);
  }

  /*
   * Check reachability for an individual equivalence class.
   * Depending on the action requested from the query, it will
   * stop the search when it has found a relevant path and return it.
   * Will return null if it found no path.
   */
  @Nullable
  private Tuple<Path, FlowDisposition> reachable(
      int alphaIdx, BitSet flags, Set<GraphNode> sources, Set<GraphNode> sinks) {
    Queue<GraphNode> todo = new ArrayDeque<>();

    GraphLink[] predecessors = new GraphLink[_allNodes.size()];
    BitSet visited = new BitSet(_allNodes.size());
    todo.addAll(sources);
    for (GraphNode source : sources) {
      predecessors[source.getIndex()] = null;
    }

    while (!todo.isEmpty()) {
      GraphNode current = todo.remove();
      boolean isSink = sinks.contains(current.owner());
      visited.set(current.getIndex());
      int numLinks = 0;
      for (GraphLink link : _adjacencyLists.get(current.getIndex())) {
        // TODO: make sure the link is active
        if (_labels[link.getIndex()].get(alphaIdx)) {
          numLinks++;
          GraphNode neighbor = link.getTarget();
          // packet is dropped, figure out what went wrong

          if (!visited.get(neighbor.getIndex())) {
            todo.add(neighbor);
            predecessors[neighbor.getIndex()] = link;
          }

          if (isSink) {
            if (neighbor.isDropNode()) {
              String name = current.getName();

              if (flags.get(ACCEPT_FLAG)) {
                // TODO: what if there is an ACL on the last interface?
                if (!link.getSourceIface().equals("null_interface")) {
                  // packet accepted at a destination
                  // TODO: what is the exact definition of accepted here?
                  // TODO: handle difference with NEIGHBOR_UNREACHABLE_OR_EXITS_NETWORK
                  return new Tuple<>(
                      reconstructPath(predecessors, neighbor), FlowDisposition.ACCEPTED);
                }
              }

              if ((flags.get(DROP_ACL_IN_FLAG) || flags.get(DROP_ACL_FLAG) || flags.get(DROP_FLAG))
                  && name.startsWith("ACL-IN")) {
                return new Tuple<>(
                    reconstructPath(predecessors, neighbor), FlowDisposition.DENIED_IN);
              }
              if ((flags.get(DROP_ACL_OUT_FLAG) || flags.get(DROP_ACL_FLAG) || flags.get(DROP_FLAG))
                  && name.startsWith("ACL-OUT")) {
                return new Tuple<>(
                    reconstructPath(predecessors, neighbor), FlowDisposition.DENIED_OUT);
              }
              if ((flags.get(DROP_NULL_ROUTE_FLAG) || flags.get(DROP_FLAG))
                  && link.getSourceIface().equals("null_interface")) {
                return new Tuple<>(
                    reconstructPath(predecessors, neighbor), FlowDisposition.NULL_ROUTED);
              }
            }
          }
        }
      }
      // the router doesn't know how to forward the packet
      if (isSink && (flags.get(DROP_NO_ROUTE_FLAG) || flags.get(DROP_FLAG)) && numLinks == 0) {
        return new Tuple<>(reconstructPath(predecessors, current), FlowDisposition.NO_ROUTE);
      }
    }
    return null;
  }
}
