parser grammar Cisco_ospf;

import Cisco_common;

options {
   tokenVocab = CiscoLexer;
}

area_ipv6_ro_stanza
:
   AREA ~NEWLINE* NEWLINE
;

area_nssa_ro_stanza
:
   AREA
   (
      area_int = DEC
      | area_ip = IP_ADDRESS
   ) NSSA
   (
      NO_SUMMARY
      | DEFAULT_INFORMATION_ORIGINATE
   )* NEWLINE
;

area_stub_ro_stanza
:
   AREA
   (
      area_int = DEC
      | area_ip = IP_ADDRESS
   ) STUB
   (
      NO_SUMMARY
   )* NEWLINE
;

area_xr_ro_stanza
:
   AREA
   (
      area_int = DEC
      | area_ip = IP_ADDRESS
   ) NEWLINE
   (
      authentication_xr_ro_stanza
      | nssa_xr_ro_stanza
      | interface_xr_ro_stanza
   )*
;

authentication_xr_ro_stanza
:
   AUTHENTICATION MESSAGE_DIGEST? NEWLINE
;

nssa_xr_ro_stanza
:
   NSSA
   (
      (
         DEFAULT_INFORMATION_ORIGINATE
         (
            (
               METRIC DEC
            )
            |
            (
               METRIC_TYPE DIGIT
            )
         )*
      )
      | NO_REDISTRIBUTION
      | NO_SUMMARY
   )* NEWLINE
;

auto_cost_ipv6_ro_stanza
:
   AUTO_COST REFERENCE_BANDWIDTH DEC NEWLINE
;

default_information_ipv6_ro_stanza
:
   DEFAULT_INFORMATION ~NEWLINE* NEWLINE
;

default_information_ro_stanza
:
   DEFAULT_INFORMATION ORIGINATE
   (
      (
         METRIC metric = DEC
      )
      |
      (
         METRIC_TYPE metric_type = DEC
      )
      | ALWAYS
      |
      (
         ROUTE_MAP map = VARIABLE
      )
      |
      (
         ROUTE_POLICY policy = VARIABLE
      )
      | TAG DEC
   )* NEWLINE
;

distance_ipv6_ro_stanza
:
   DISTANCE value = DEC NEWLINE
;

distance_ro_stanza
:
   DISTANCE value = DEC NEWLINE
;

interface_xr_ro_stanza
:
   INTERFACE name = interface_name NEWLINE interface_xr_ro_tail*
;

interface_xr_ro_tail
:
   (
      NETWORK
      (
         BROADCAST
         | NON_BROADCAST
         |
         (
            POINT_TO_MULTIPOINT NON_BROADCAST?
         )
         | POINT_TO_POINT
      )
      | PRIORITY DEC
      | PASSIVE
      (
         ENABLE
         | DISABLE
      )?
   ) NEWLINE
;

ipv6_ro_stanza
:
   area_ipv6_ro_stanza
   | auto_cost_ipv6_ro_stanza
   | default_information_ipv6_ro_stanza
   | distance_ipv6_ro_stanza
   | log_adjacency_changes_ipv6_ro_stanza
   | maximum_paths_ipv6_ro_stanza
   | passive_interface_ipv6_ro_stanza
   | redistribute_ipv6_ro_stanza
   | router_id_ipv6_ro_stanza
;

ipv6_router_ospf_stanza
:
   IPV6 ROUTER OSPF procnum = DEC NEWLINE
   (
      rosl += ipv6_ro_stanza
   )+
;

log_adjacency_changes_ipv6_ro_stanza
:
   LOG_ADJACENCY_CHANGES NEWLINE
;

maximum_paths_ipv6_ro_stanza
:
   MAXIMUM_PATHS DEC NEWLINE
;

maximum_paths_ro_stanza
:
   MAXIMUM_PATHS DEC NEWLINE
;

network_ro_stanza
:
   NETWORK
   (
      (
         ip = IP_ADDRESS wildcard = IP_ADDRESS
      )
      | prefix = IP_PREFIX
   ) AREA
   (
      area_int = DEC
      | area_ip = IP_ADDRESS
   ) NEWLINE
;

null_ro_stanza
:
   null_standalone_ro_stanza
;

null_rov3_stanza
:
   null_standalone_rov3_stanza
   | unrecognized_line
;

null_standalone_ro_stanza
:
   NO?
   (
      (
         AREA
         (
            DEC
            | IP_ADDRESS
         ) AUTHENTICATION
      )
      | AUTHENTICATION MESSAGE_DIGEST?
      | AUTO_COST
      | BFD
      | DISTRIBUTE_LIST
      | GRACEFUL_RESTART
      | LOG
      | LOG_ADJACENCY_CHANGES
      | MAX_LSA
      | MAX_METRIC
      | MTU_IGNORE
      | NSF
      | NSR
      | RFC1583COMPATIBILITY
   ) ~NEWLINE* NEWLINE
;

null_standalone_rov3_stanza
:
   (
      AREA
      | DEAD_INTERVAL
      | DEFAULT_INFORMATION
      | HELLO_INTERVAL
      | INTERFACE
      | LOG
      | MTU_IGNORE
      | NETWORK
      | NSSA
      | NSR
      | PASSIVE
      | PRIORITY
      | ROUTER_ID
   ) ~NEWLINE* NEWLINE
;

passive_interface_ipv6_ro_stanza
:
   NO? PASSIVE_INTERFACE ~NEWLINE* NEWLINE
;

passive_interface_default_ro_stanza
:
   PASSIVE_INTERFACE DEFAULT NEWLINE
;

passive_interface_ro_stanza
:
   NO? PASSIVE_INTERFACE i = interface_name NEWLINE
;

redistribute_bgp_ro_stanza
:
   REDISTRIBUTE BGP as = DEC
   (
      (
         METRIC metric = DEC
      )
      |
      (
         METRIC_TYPE type = DEC
      )
      |
      (
         ROUTE_MAP map = VARIABLE
      )
      | subnets = SUBNETS
      |
      (
         TAG tag = DEC
      )
   )* NEWLINE
;

redistribute_ipv6_ro_stanza
:
   REDISTRIBUTE ~NEWLINE* NEWLINE
;

redistribute_connected_ro_stanza
:
   REDISTRIBUTE CONNECTED
   (
      (
         METRIC metric = DEC
      )
      |
      (
         METRIC_TYPE type = DEC
      )
      |
      (
         ROUTE_MAP map = VARIABLE
      )
      | subnets = SUBNETS
      |
      (
         TAG tag = DEC
      )
   )* NEWLINE
;

redistribute_rip_ro_stanza
:
   REDISTRIBUTE RIP ~NEWLINE* NEWLINE
;

redistribute_static_ro_stanza
:
   REDISTRIBUTE STATIC
   (
      (
         METRIC metric = DEC
      )
      |
      (
         METRIC_TYPE type = DEC
      )
      |
      (
         ROUTE_MAP map = VARIABLE
      )
      | subnets = SUBNETS
      |
      (
         TAG tag = DEC
      )
   )* NEWLINE
;

ro_stanza
:
   area_nssa_ro_stanza
   | area_stub_ro_stanza
   | area_xr_ro_stanza
   | default_information_ro_stanza
   | distance_ro_stanza
   | maximum_paths_ro_stanza
   | network_ro_stanza
   | null_ro_stanza
   | passive_interface_default_ro_stanza
   | passive_interface_ro_stanza
   | redistribute_bgp_ro_stanza
   | redistribute_connected_ro_stanza
   | redistribute_rip_ro_stanza
   | redistribute_static_ro_stanza
   | router_id_ro_stanza
   | summary_address_ro_stanza
   | unrecognized_line
;

router_id_ipv6_ro_stanza
:
   ROUTER_ID ~NEWLINE* NEWLINE
;

router_id_ro_stanza
:
   ROUTER_ID ip = IP_ADDRESS NEWLINE
;

router_ospf_stanza
:
   ROUTER OSPF procnum = DEC
   (
      VRF vrf = variable
   )? NEWLINE router_ospf_stanza_tail
;

router_ospf_stanza_tail
:
   (
      rosl += ro_stanza
   )+
;

router_ospfv3_stanza
:
   ROUTER OSPFV3 procnum = DEC NEWLINE null_rov3_stanza*
;

summary_address_ro_stanza
:
   SUMMARY_ADDRESS network = IP_ADDRESS mask = IP_ADDRESS NOT_ADVERTISE?
   NEWLINE
;
