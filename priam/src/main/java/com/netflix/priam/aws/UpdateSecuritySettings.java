/**
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.priam.aws;

import java.util.List;
import java.util.Random;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.priam.IConfiguration;
import com.netflix.priam.identity.IMembership;
import com.netflix.priam.identity.IPriamInstanceFactory;
import com.netflix.priam.identity.InstanceIdentity;
import com.netflix.priam.identity.PriamInstance;
import com.netflix.priam.scheduler.SimpleTimer;
import com.netflix.priam.scheduler.Task;
import com.netflix.priam.scheduler.TaskTimer;

/**
 * this class will associate an Public IP's with a new instance so they can talk
 * across the regions.
 * 
 * Requirement: 1) Nodes in the same region needs to be able to talk to each
 * other. 2) Nodes in other regions needs to be able to talk to the others in
 * the other region.
 * 
 * Assumption: 1) IPriamInstanceFactory will provide the membership... and will
 * be visible across the regions 2) IMembership amazon or any other
 * implementation which can tell if the instance is part of the group (ASG in
 * amazons case).
 * 
 */
@Singleton
public class UpdateSecuritySettings extends Task
{
    public static final String JOBNAME = "Update_SG";
    public static boolean firstTimeUpdated = false;

    private static final Random ran = new Random();
    private final IMembership membership;
    private final IPriamInstanceFactory factory;

    @Inject
    public UpdateSecuritySettings(IConfiguration config, IMembership membership, IPriamInstanceFactory factory)
    {
        super(config);
        this.membership = membership;
        this.factory = factory;
    }

    /**
     * Seeds nodes execute this at the specifed interval.
     * Other nodes run only on startup.
     * Seeds in cassandra are the first node in each Availablity Zone.
     */
    @Override
    public void execute()
    {
        // if seed dont execute.
		// we want to add/delete both the Storage port and the JMX port in the ACL
		// if the cluster is using SSL we should add the ssl storage port instead
        //int port = config.getSSLStoragePort();
	int port = config.getStoragePort();
        int portJMX = config.getJmxPort();
        int portOpsCtr = 61620;
        List<String> acls = membership.listACL(port, port);
        List<String> aclsJMX = membership.listACL(portJMX, portJMX);
        List<String> aclsOpsCtr = membership.listACL(portOpsCtr, portOpsCtr);
        List<PriamInstance> instances = factory.getAllIds(config.getAppName());

        // iterate to add...
        List<String> add = Lists.newArrayList();
        List<String> addJMX = Lists.newArrayList();
        List<String> addOpsCtr = Lists.newArrayList();
        for (PriamInstance instance : factory.getAllIds(config.getAppName()))
        {
            String range = instance.getHostIP() + "/32";
            if (!acls.contains(range) && !add.contains(range))
                add.add(range);
            if (!aclsJMX.contains(range) && !addJMX.contains(range))
                addJMX.add(range);
            if (!aclsOpsCtr.contains(range) && !addJMX.contains(range))
                addOpsCtr.add(range);
        }
        if (add.size() > 0)
        {
            membership.addACL(add, port, port);
            firstTimeUpdated = true;
        }
        if (addJMX.size() > 0)
        {
            membership.addACL(addJMX, portJMX, portJMX);
        }
        if (addOpsCtr.size() > 0)
        {
            membership.addACL(addOpsCtr, portOpsCtr, portOpsCtr);
        }

        // just iterate to generate ranges.
        List<String> currentRanges = Lists.newArrayList();
        for (PriamInstance instance : instances)
        {
            String range = instance.getHostIP() + "/32";
            currentRanges.add(range);
        }

        // iterate to remove...
        List<String> remove = Lists.newArrayList();
        List<String> removeJMX = Lists.newArrayList();
        List<String> removeOpsCtr = Lists.newArrayList();
        for (String acl : acls)
            if (!currentRanges.contains(acl) && !remove.contains(acl)) // if not found then remove....
                remove.add(acl);
        for (String aclJMX : aclsJMX)
            if (!currentRanges.contains(aclJMX) && !removeJMX.contains(aclJMX)) // if not found then remove....
                removeJMX.add(aclJMX);
        for (String aclOpsCtr : aclsOpsCtr)
            if (!currentRanges.contains(aclOpsCtr) && !removeOpsCtr.contains(aclOpsCtr)) // if not found then remove....
                removeOpsCtr.add(aclOpsCtr);

        if (remove.size() > 0)
        {
            membership.removeACL(remove, port, port);
            firstTimeUpdated = true;
        }
        if (removeJMX.size() > 0)
        {
            membership.removeACL(removeJMX, portJMX, portJMX);
        }
        if (removeOpsCtr.size() > 0)
        {
            membership.removeACL(removeOpsCtr, portOpsCtr, portOpsCtr);
        }

    }

    public static TaskTimer getTimer(InstanceIdentity id)
    {
        SimpleTimer return_;
        if (id.isSeed())
            return_ = new SimpleTimer(JOBNAME, 120 * 1000 + ran.nextInt(120 * 1000));
        else
            return_ = new SimpleTimer(JOBNAME);
        return return_;
    }

    @Override
    public String getName()
    {
        return JOBNAME;
    }
}
