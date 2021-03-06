package org.batfish.representation.juniper;

import static org.junit.Assert.assertEquals;

import org.batfish.common.Warnings;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.ConfigurationFormat;
import org.batfish.datamodel.HeaderSpace;
import org.batfish.datamodel.IpSpace;
import org.batfish.datamodel.IpWildcard;
import org.batfish.datamodel.LineAction;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.RouteFilterLine;
import org.batfish.datamodel.RouteFilterList;
import org.batfish.datamodel.SubRange;
import org.batfish.datamodel.TraceElement;
import org.batfish.datamodel.acl.MatchHeaderSpace;
import org.junit.Before;
import org.junit.Test;

/** Test for {@link FwFromPrefixList} */
public class FwFromPrefixListTest {
  private JuniperConfiguration _jc;
  private Warnings _w;
  private Configuration _c;

  private static final String BASE_PREFIX_LIST_NAME = "prefixList";
  private static final String BASE_IP_PREFIX = "1.2.3.4/32";

  @Before
  public void setup() {
    _jc = new JuniperConfiguration();
    _jc.getMasterLogicalSystem()
        .getPrefixLists()
        .put(BASE_PREFIX_LIST_NAME, new PrefixList(BASE_PREFIX_LIST_NAME));
    _w = new Warnings();
    _c = new Configuration("test", ConfigurationFormat.FLAT_JUNIPER);
    RouteFilterList rflist = new RouteFilterList(BASE_PREFIX_LIST_NAME);
    RouteFilterLine rfline =
        new RouteFilterLine(LineAction.PERMIT, Prefix.parse(BASE_IP_PREFIX), SubRange.singleton(0));
    rflist.addLine(rfline);
    _c.getRouteFilterLists().put(BASE_PREFIX_LIST_NAME, rflist);
  }

  @Test
  public void testToAclLineMatchExpr() {
    IpSpace baseIpSpace = IpWildcard.parse(BASE_IP_PREFIX).toIpSpace();

    FwFromPrefixList fwFrom = new FwFromPrefixList(BASE_PREFIX_LIST_NAME);

    // Apply base IP prefix to headerSpace with null IpSpace
    assertEquals(
        fwFrom.toAclLineMatchExpr(_jc, _c, _w),
        new MatchHeaderSpace(
            HeaderSpace.builder().setSrcOrDstIps(baseIpSpace).build(),
            TraceElement.of("Matched prefix-list prefixList")));
  }
}
