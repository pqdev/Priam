package com.netflix.priam;


import com.amazonaws.services.simpledb.AmazonSimpleDBClient;
import com.amazonaws.services.simpledb.model.*;
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
    private final String IID = SystemUtils.getDataFromUrl("http://169.254.169.254/latest/meta-data/instance-id").trim();
    private static String INSTANCE_ID="instanceId";
    private static String APP_ID = "appId"; // ASG
    private static String PROPERTY = "property";
    private static String PROPERTY_VALUE = "value";
    private static String REGION = "region";
    private static String KEYSTORE = "keystore";
    private static String TRUSTSTORE = "truststore";
    private static String ITEMNAME = "itemName";

    private static final String DOMAIN = "PriamProperties";
    private static String ALL_QUERY = "select * from " + DOMAIN + " where " + APP_ID + "='%s'";
    public static final String DOMAIN_SECURITY = "InstanceSecurity";
    private static String SECURITY_QUERY = "select itemName, keystore, instanceId, updateTimestamp, truststore from " + DOMAIN_SECURITY + " where " + APP_ID + "='%s'";
    private static String SECURITY_QUERY2 = "select keystore, truststore from " + DOMAIN_SECURITY + " where " + INSTANCE_ID + "='%s'";

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
            logger.info(String.format("first request is: %s", request.toString()));
            request.setNextToken(nextToken);
            SelectResult result = simpleDBClient.select(request);
            nextToken = result.getNextToken();
            Iterator<Item> itemiter = result.getItems().iterator();
            logger.info("first response about to iter over");
            while (itemiter.hasNext())
              addProperty(itemiter.next());
            logger.info("first response after iter");
        } 
        while (nextToken != null);

        // read in additional info from simpleDB
        logger.info("Start of new SimpleDB section");

        // query - pull keystore, truststore where instanceId=IID.
        // If success, call addPropertySecurity and return.
        SelectRequest request2 = new SelectRequest(String.format(SECURITY_QUERY2, IID)).withConsistentRead(true);
        logger.info("request2="+request2.toString());

        SelectResult result2 = simpleDBClient.select(request2);
        logger.info("SimpleDB2,result2="+result2.toString());

        Iterator<Item> itemiter2 = result2.getItems().iterator();
        if(itemiter2.hasNext()) {
            addPropertySecurity(itemiter2.next());
            String keystore2 = data.get(KEYSTORE);
            String truststore2 = data.get(TRUSTSTORE);

            if (keystore2.length() > 0 && truststore2.length() > 0) {
                logger.info("iid " + IID + " already in security table with keystore " + keystore2 + " and truststore" + truststore2);
                return;
            }
        }

        // match not found in table.
        Boolean match = false;
        nextToken = null;
        do
        {
            SelectRequest request = new SelectRequest(String.format(SECURITY_QUERY, appid)).withConsistentRead(true);

            logger.info("request="+request.toString());
            request.setNextToken(nextToken);
            SelectResult result = simpleDBClient.select(request);
            nextToken = result.getNextToken();
            Iterator<Item> itemiter = result.getItems().iterator();
            logger.info("SimpleDB2,result="+result.toString());
            while (itemiter.hasNext()) {
                addPropertySecurity(itemiter.next());
                String iidRow = data.get(INSTANCE_ID);
                String keystore = data.get(KEYSTORE);
                String truststore = data.get(TRUSTSTORE);
                logger.info("iid[" + iidRow + "]");

                if(iidRow.equals("")) {
                     // this keystore may be available
                     // try to update this row with the instanceId
                      // pi.setKeystore(iid);
                     PutAttributesRequest req = new PutAttributesRequest()
                         .withExpected(new UpdateCondition(INSTANCE_ID, "", true))
                         .withDomainName(DOMAIN_SECURITY)
                         .withItemName(data.get(ITEMNAME))
                         .withAttributes(new ReplaceableAttribute(INSTANCE_ID, IID, true));
                     logger.info("req=" + req.toString());
                     simpleDBClient.putAttributes(req);
                     GetAttributesRequest getReq = new GetAttributesRequest(DOMAIN_SECURITY, data.get(ITEMNAME)).withConsistentRead(true);
                     logger.info("getReq="+getReq.toString());
                     GetAttributesResult attResult = simpleDBClient.getAttributes(getReq);
                     logger.info("attResult="+attResult.toString());

                     Boolean breakNow=false;
                     for (Attribute attr : attResult.getAttributes()) {
                         logger.info("name: " + attr.getName() + "\tvalue: " + attr.getValue());
                         if (attr.getName().equals(INSTANCE_ID) && attr.getValue().equals(IID)) {
                             logger.info("found place for iid " + IID + " on row " + data.get(ITEMNAME) + "; breaking");
                             match = true;
                             breakNow=true;
                         }
                     } // for
                    if(breakNow) {
                        break;
                    }
                 } // if
                else {
                    logger.info("iidRow is not null:"+iidRow);
                }
            } // while more rows

            if(! match) {
                logger.error("Could not acquire keystore and truststore files");
                // throw exception here? or let it fail later?
            }
        } while (nextToken != null);
    }

    private void addProperty(Item item) 
    {
        logger.info("item=" + item.toString());
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
    }

    private void addPropertySecurity(Item item)
    {
        logger.info("item=" + item.toString());
        Iterator<Attribute> attrs = item.getAttributes().iterator();

        String itemName = item.getName();
        logger.info("itemName=" + itemName);
        data.put(ITEMNAME, itemName);

        String ks = "";
        String iid = "";
        String ts = "";
        while (attrs.hasNext())
        {
            Attribute att = attrs.next();
            if (att.getName().equals(KEYSTORE))
                ks = att.getValue();
            else if (att.getName().equals(INSTANCE_ID))
                iid = att.getValue();
            else if (att.getName().equals(TRUSTSTORE))
                ts = att.getValue();
        }

        data.put(KEYSTORE, ks);
        logger.info("key=" + KEYSTORE + ",value=" + ks);
        data.put(TRUSTSTORE, ts);
        logger.info("key=" + TRUSTSTORE + ",value=" + ts);
        data.put(INSTANCE_ID, iid);
        logger.info("key=" + INSTANCE_ID + ",value=" + iid);
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
