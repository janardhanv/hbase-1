/**
 * Copyright The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import com.google.common.collect.Sets;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.LargeTests;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.group.GroupAdminClient;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.group.GroupAdminEndpoint;
import org.apache.hadoop.hbase.group.GroupBasedLoadBalancer;
import org.apache.hadoop.hbase.group.GroupInfo;
import org.apache.hadoop.hbase.group.GroupMasterObserver;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.JVMClusterUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category(LargeTests.class)
public class TestGroupsWithDeadServers {
	private static HBaseTestingUtility TEST_UTIL;
	private static HMaster master;
	private static Random rand;
  private static HBaseAdmin admin;

	@BeforeClass
	public static void setUp() throws Exception {
		TEST_UTIL = new HBaseTestingUtility();
		TEST_UTIL.getConfiguration().set(
				HConstants.HBASE_MASTER_LOADBALANCER_CLASS,
				GroupBasedLoadBalancer.class.getName());
    TEST_UTIL.getConfiguration().set("hbase.coprocessor.master.classes",
        GroupMasterObserver.class.getName()+","+
        GroupAdminEndpoint.class.getName());
		TEST_UTIL.getConfiguration().setInt(
				"hbase.master.assignment.timeoutmonitor.period", 2000);
		TEST_UTIL.getConfiguration().setInt(
				"hbase.master.assignment.timeoutmonitor.timeout", 5000);
		TEST_UTIL.startMiniCluster(4);
		MiniHBaseCluster cluster = TEST_UTIL.getHBaseCluster();
		master = cluster.getMaster();
		rand = new Random();
    admin = TEST_UTIL.getHBaseAdmin();
    while(!((GroupBasedLoadBalancer)master.getLoadBalancer()).isOnline()) {
      Thread.sleep(100);
    }
	}

	@AfterClass
	public static void tearDown() throws Exception {
		TEST_UTIL.shutdownMiniCluster();
	}

	@Test
	public void testGroupWithOnlineServers() throws IOException, InterruptedException{
    GroupAdminClient groupAdmin = new GroupAdminClient(master.getConfiguration());
		String newRSGroup = "group-" + rand.nextInt();
		String tableNameTwo = "TABLE-" + rand.nextInt();
		byte[] tableTwoBytes = Bytes.toBytes(tableNameTwo);
		String familyName = "family" + rand.nextInt();
		byte[] familyTwoBytes = Bytes.toBytes(familyName);
    int baseNumRegions = TEST_UTIL.getMetaTableRows().size();
		int NUM_REGIONS = 4;

		GroupInfo defaultInfo = groupAdmin.getGroupInfo(GroupInfo.DEFAULT_GROUP);
		assertTrue(defaultInfo.getServers().size() == 4);
		TestGroups.addGroup(groupAdmin, newRSGroup, 2);
		defaultInfo = groupAdmin.getGroupInfo(GroupInfo.DEFAULT_GROUP);
		assertTrue(defaultInfo.getServers().size() == 2);
		assertTrue(groupAdmin.getGroupInfo(newRSGroup).getServers().size() == 2);
		HTable ht = TEST_UTIL.createTable(tableTwoBytes, familyTwoBytes);
		// All the regions created below will be assigned to the default group.
		assertTrue(TEST_UTIL.createMultiRegions(master.getConfiguration(), ht,
				familyTwoBytes, NUM_REGIONS) == NUM_REGIONS);
		TEST_UTIL.waitUntilAllRegionsAssigned(baseNumRegions+NUM_REGIONS);
		Set<HRegionInfo> regions = listOnlineRegionsOfGroup(GroupInfo.DEFAULT_GROUP);
		assertTrue(regions.size() >= NUM_REGIONS);
    //move table to new group
    admin.disableTable(tableNameTwo);
    groupAdmin.moveTables(Sets.newHashSet(tableNameTwo), newRSGroup);
    admin.enableTable(tableTwoBytes);

		TEST_UTIL.waitUntilAllRegionsAssigned(baseNumRegions+NUM_REGIONS);
		//Move the ROOT and META regions to default group.
		ServerName serverForRoot =
        ServerName.findServerWithSameHostnamePort(master.getServerManager().getOnlineServersList(),
            ServerName.parseServerName(defaultInfo.getServers().iterator().next()));
		master.move(HRegionInfo.ROOT_REGIONINFO.getEncodedNameAsBytes(), Bytes.toBytes(serverForRoot.toString()));
		master.move(HRegionInfo.FIRST_META_REGIONINFO.getEncodedNameAsBytes(), Bytes.toBytes(serverForRoot.toString()));
		while (master.getAssignmentManager().isRegionsInTransition()){
			Thread.sleep(10);
		}
		Set<HRegionInfo> newGrpRegions = listOnlineRegionsOfGroup(newRSGroup);
		assertTrue(newGrpRegions.size() == NUM_REGIONS);
		MiniHBaseCluster hbaseCluster = TEST_UTIL.getHBaseCluster();
		// Now we kill all the region servers in the new group.
		Set<String> serverNames = groupAdmin.getGroupInfo(newRSGroup).getServers();
		for (String sName : serverNames) {
			int serverNumber = getServerNumber(
					hbaseCluster.getRegionServerThreads(), sName);
			assert (serverNumber != -1);
			hbaseCluster.stopRegionServer(serverNumber, false);
		}
		//wait till all the regions come transition state.
    int tries = 10;
		while (listOnlineRegionsOfGroup(newRSGroup).size() != 0 && tries-- > 0){
			Thread.sleep(100);
		}
		newGrpRegions = listOnlineRegionsOfGroup(newRSGroup);
    assertTrue("Number of online regions in" + newRSGroup + " " + newGrpRegions.size(),
      newGrpRegions.size() == 0);
		regions = listOnlineRegionsOfGroup(GroupInfo.DEFAULT_GROUP);
		assertEquals(3, regions.size());
		startServersAndMove(groupAdmin, 1, newRSGroup);
		while(master.getAssignmentManager().isRegionsInTransition()){
			Thread.sleep(5);
		}
		scanTableForPositiveResults(ht);
		newGrpRegions = listOnlineRegionsOfGroup(newRSGroup);
		assertTrue(newGrpRegions.size() == NUM_REGIONS);
	}

	private int getServerNumber(List<JVMClusterUtil.RegionServerThread> servers, String sName){
		int i = 0;
		for(JVMClusterUtil.RegionServerThread rs : servers){
			if(sName.equals(rs.getRegionServer().getServerName().getHostAndPort())){
				return i;
			}
			i++;
		}
		return -1;
	}
	
	private void scanTableForPositiveResults(HTable ht) throws IOException{
		ResultScanner s = null;
		try {
			Scan scan = new Scan();
			s = ht.getScanner(scan);
		} finally {
			if (s != null) {
				s.close();
			}
		}
	}

	private void startServersAndMove(GroupAdminClient groupAdmin, int numServers,
			String groupName) throws IOException, InterruptedException {
		MiniHBaseCluster hbaseCluster = TEST_UTIL.getHBaseCluster();
		ServerName newServer;
		for (int i = 0; i < numServers; i++) {
			newServer = hbaseCluster.startRegionServer().getRegionServer()
					.getServerName();
			// Make sure that the server manager reports the new online servers.
			while (ServerName.findServerWithSameHostnamePort(master
					.getServerManager().getOnlineServersList(), newServer) == null) {
				Thread.sleep(5);
			}
			assertTrue(groupAdmin.getGroupInfo(GroupInfo.DEFAULT_GROUP)
          .containsServer(newServer.getHostAndPort()));
      Set<String> set = new TreeSet<String>();
      set.add(newServer.getHostAndPort());
			groupAdmin.moveServers(set, groupName);
			assertTrue(groupAdmin.getGroupInfo(groupName).containsServer(
          newServer.getHostAndPort()));
		}
	}

  private Set<HRegionInfo> listOnlineRegionsOfGroup(String groupName) throws IOException {
     if (groupName == null) {
      throw new NullPointerException("groupName can't be null");
    }

    GroupInfo groupInfo = ((GroupBasedLoadBalancer)master.getLoadBalancer())
        .getGroupInfoManager().getGroup(groupName);
    if (groupInfo == null) {
			return null;
		}
    NavigableSet<HRegionInfo> regions = new TreeSet<HRegionInfo>();
    Set<String> servers = groupInfo.getServers();
    Map<String,List<HRegionInfo>> assignments = getOnlineRegions();
    for(ServerName serverName: master.getServerManager().getOnlineServersList()) {
      String hostPort = serverName.getHostAndPort();
      if (servers.contains(hostPort) && assignments.containsKey(hostPort)) {
        regions.addAll(assignments.get(hostPort));
      }
    }
		return regions;
	}

  private Map<String,List<HRegionInfo>> getOnlineRegions() throws IOException {
    Map<String,List<HRegionInfo>> result = new HashMap<String, List<HRegionInfo>>();
    for(Map.Entry<ServerName, java.util.List<HRegionInfo>> el:
        master.getAssignmentManager().getAssignments().entrySet()) {
      if (!result.containsKey(el.getKey().getHostAndPort())) {
        result.put(el.getKey().getHostAndPort(),new LinkedList<HRegionInfo>());
      }
      result.get(el.getKey().getHostAndPort()).addAll(el.getValue());
    }
    return result;
  }

}
