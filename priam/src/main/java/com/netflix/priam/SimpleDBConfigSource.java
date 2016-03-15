package com.netflix.priam;

import com.amazonaws.services.simpledb.AmazonSimpleDBClient;
import com.amazonaws.services.simpledb.model.Attribute;
import com.amazonaws.services.simpledb.model.Item;
import com.amazonaws.services.simpledb.model.SelectRequest;
import com.amazonaws.services.simpledb.model.SelectResult;
import com.amazonaws.services.simpledb.model.GetAttributesRequest;
import com.amazonaws.services.simpledb.model.GetAttributesResult;
import com.amazonaws.services.simpledb.model.PutAttributesRequest;
import com.amazonaws.services.simpledb.model.ReplaceableAttribute;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.netflix.priam.utils.SystemUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Iterator;
import java.util.Map;

/**
 * Loads config data from SimpleDB.  {@link #intialize(String, String)} will query the SimpleDB domain "PriamProperties"
 * for any potential configurations.  The domain is set up to support multiple different clusters; this is done by using
 * amazon's auto scaling groups (ASG).
 * <p/>
 * Schema <ul>
 *   <li>"appId" // ASG up to first instance of '-'.  So ASG name priam-test will create appId priam, ASG priam_test
 *   will create appId priam_test.</li>
 *   <li>"property" // key to use for configs.</li>
 *   <li>"value" // value to set for the given property/key.</li>
 *   <li>"region" // region the config belongs to.  If left empty, then applies to all regions.</li>
 * </ul> }
 */
public final class SimpleDBConfigSource extends AbstractConfigSource 
{
    private static final Logger logger = LoggerFactory.getLogger(SimpleDBConfigSource.class.getName());
    private final String INSTANCE_ID = SystemUtils.getDataFromUrl("http://169.254.169.254/latest/meta-data/instance-id").trim();
    private static String APP_ID = "appId"; // ASG
    private static String PROPERTY = "property";
    private static String PROPERTY_VALUE = "value";
    private static String REGION = "region";
    private static String KEYSTORE = "keystore";
    private static String ITEMNAME = "itemName";

    private static final String DOMAIN = "PriamProperties";
    private static String ALL_QUERY = "select * from " + DOMAIN + " where " + APP_ID + "='%s'";
    public static final String DOMAIN_SECURITY = "InstanceSecurity";
    private static String SECURITY_QUERY = "select itemName, keystore, instanceId, updateTimestamp, truststore from " + DOMAIN_SECURITY + " where " + APP_ID + "='%s'";

    private final Map<String, String> data = Maps.newConcurrentMap();
    private final ICredential provider;

    @Inject
    public SimpleDBConfigSource(final ICredential provider) 
    {
        this.provider = provider;
    }

    @Override
    public void intialize(final String asgName, final String region) 
    {
        super.intialize(asgName, region);

        // End point is us-east-1
        AmazonSimpleDBClient simpleDBClient = new AmazonSimpleDBClient(provider.getAwsCredentialProvider());

        String nextToken = null;
        String appid = asgName.lastIndexOf('-') > 0 ? asgName.substring(0, asgName.indexOf('-')) : asgName;
        logger.info(String.format("appid used to fetch properties is: %s", appid));
        do 
        {
            SelectRequest request = new SelectRequest(String.format(ALL_QUERY, appid));
            request.setNextToken(nextToken);
            SelectResult result = simpleDBClient.select(request);
            nextToken = result.getNextToken();
            Iterator<Item> itemiter = result.getItems().iterator();
            while (itemiter.hasNext())
              addProperty(itemiter.next());

        } 
        while (nextToken != null);

        // read in additional info from simpleDB
        System.out.println("Start of new SimpleDB section");

        nextToken = null;
        do
        {
            SelectRequest request = new SelectRequest(String.format(SECURITY_QUERY, appid));
            System.out.println("request="+request.toString());
            request.setNextToken(nextToken);
            SelectResult result = simpleDBClient.select(request);
            nextToken = result.getNextToken();
            Iterator<Item> itemiter = result.getItems().iterator();
            System.out.println("SimpleDB2,result="+result.toString());
            while (itemiter.hasNext()) {
                System.out.println("SimpleDB3,itemiter="+itemiter.toString());
                addProperty(itemiter.next());
                String ks = data.get(KEYSTORE);

                if(ks == null) {
                    System.out.println("SimpleDB4");
                     // this keystore may be available
                     // try to update this row with the instanceId
                      // pi.setKeystore(iid);
                     PutAttributesRequest req = new PutAttributesRequest()
                         .withDomainName(DOMAIN_SECURITY)
                         .withItemName(data.get(ITEMNAME))
                         .withAttributes(new ReplaceableAttribute("instanceId", INSTANCE_ID, false));
                    System.out.println("req="+req.toString());
                     simpleDBClient.putAttributes(req);
                    System.out.println("SimpleDB5");
                  GetAttributesRequest getReq = new GetAttributesRequest(DOMAIN_SECURITY, "instanceId").withConsistentRead(true);
                     GetAttributesResult attResult = simpleDBClient.getAttributes(getReq);

                  for (Attribute attr : attResult.getAttributes()) {
                         System.out.println("name: " + attr.getName() + "\tvalue: " + attr.getValue());
                     } // for
                 } // if
            } // while more rows
            System.out.println("SimpleDB6");
        } while (nextToken != null);
        System.out.println("SimpleDB7");
    }

    private void addProperty(Item item) 
    {
        System.out.println("item="+item.toString());
        Iterator<Attribute> attrs = item.getAttributes().iterator();
        String prop = "";
        String value = "";
        String dc = "";
        while (attrs.hasNext()) 
        {
            Attribute att = attrs.next();
            if (att.getName().equals(PROPERTY))
                prop = att.getValue();
            else if (att.getName().equals(PROPERTY_VALUE))
                value = att.getValue();
            else if (att.getName().equals(REGION))
                dc = att.getValue();
        }
        // Ignore, if not this region
        if (StringUtils.isNotBlank(dc) && !dc.equals(getRegion()))
            return;
        // Override only if region is specified
        if (data.containsKey(prop) && StringUtils.isBlank(dc))
            return;
        data.put(prop, value);
        System.out.println("prop="+prop+" value="+value);
    }



    @Override
    public int size() 
    {
        return data.size();
    }

    @Override
    public String get(final String key) 
    {
        return data.get(key);
    }

    @Override
    public void set(final String key, final String value) 
    {
        Preconditions.checkNotNull(value, "Value can not be null for configurations.");
        data.put(key, value);
    }
}
