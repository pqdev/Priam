package com.netflix.priam;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
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
import com.netflix.priam.aws.S3FileSystem;
import com.netflix.priam.backup.AbstractBackupPath;
import com.netflix.priam.backup.BackupRestoreException;
import com.netflix.priam.backup.RangeReadInputStream;
import com.netflix.priam.utils.SystemUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.BufferedInputStream;
import java.io.OutputStream;
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
    private static String S3BUCKET_PREFIX = "xrs-support-prod/";
    private static final long MAX_BUFFERED_IN_STREAM_SIZE = 5 * 1024 * 1024;

    private static final String DOMAIN = "PriamProperties";
    private static String ALL_QUERY = "select * from " + DOMAIN + " where " + APP_ID + "='%s'";
    public static final String DOMAIN_SECURITY = "InstanceSecurity";
    private static String SECURITY_QUERY = "select itemName, keystore, instanceId, updateTimestamp, truststore from " + DOMAIN_SECURITY + " where " + APP_ID + "='%s'";

    private final Map<String, String> data = Maps.newConcurrentMap();
    private final Map<String, String> dataSecurity = Maps.newConcurrentMap();
    private final ICredential provider;

    @Inject
    public SimpleDBConfigSource(final ICredential provider)
    {
        this.provider = provider;
    }

    @Override
    public void intialize(final String asgName, final String region, final AmazonS3Client s3Client)
    {
        super.intialize(asgName, region);

        // End point is us-east-1
        AmazonSimpleDBClient simpleDBClient = new AmazonSimpleDBClient(provider.getAwsCredentialProvider());

        String nextToken = null;
        String appid = asgName.lastIndexOf('-') > 0 ? asgName.substring(0, asgName.indexOf('-')) : asgName;
        logger.info(String.format("appid3 used to fetch properties is: %s", appid));
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

        nextToken = null;
        do
        {
            SelectRequest request = new SelectRequest(String.format(SECURITY_QUERY, appid));
            logger.info("request="+request.toString());
            request.setNextToken(nextToken);
            SelectResult result = simpleDBClient.select(request);
            nextToken = result.getNextToken();
            Iterator<Item> itemiter = result.getItems().iterator();
            logger.info("SimpleDB2,result="+result.toString());
            while (itemiter.hasNext()) {
                logger.info("SimpleDB3,itemiter=" + itemiter.toString());

                addPropertySecurity(itemiter.next());
                String iidRow = dataSecurity.get(INSTANCE_ID);
                String keystore = dataSecurity.get(KEYSTORE);
                String truststore = dataSecurity.get(TRUSTSTORE);
                logger.info("iid[" + iidRow + "]");

                if(iidRow.equals("")) {
                    logger.info("SimpleDB4");
                     // this keystore may be available
                     // try to update this row with the instanceId
                      // pi.setKeystore(iid);
                     PutAttributesRequest req = new PutAttributesRequest()
                         .withDomainName(DOMAIN_SECURITY)
                         .withItemName(dataSecurity.get(ITEMNAME))
                         .withAttributes(new ReplaceableAttribute(INSTANCE_ID, IID, true));
                     logger.info("req=" + req.toString());
                     simpleDBClient.putAttributes(req);
                     logger.info("SimpleDB5");
                     GetAttributesRequest getReq = new GetAttributesRequest(DOMAIN_SECURITY, dataSecurity.get(ITEMNAME)).withConsistentRead(true);
                     logger.info("getReq="+getReq.toString());
                     GetAttributesResult attResult = simpleDBClient.getAttributes(getReq);
                     logger.info("attResult="+attResult.toString());

                     Boolean breakNow=false;
                     for (Attribute attr : attResult.getAttributes()) {
                         System.out.println("name: " + attr.getName() + "\tvalue: " + attr.getValue());
                         if (attr.getName().equals(INSTANCE_ID) && attr.getValue().equals(IID)) {
                             logger.info("found place for iid " + IID + " on row " + dataSecurity.get(ITEMNAME) + "; breaking");
                             breakNow=true;
                         }
                     } // for
                    if(breakNow) {
                        //download(s3Client, keystore);
                        //download(s3Client, truststore);
                        break;
                    }
                 } // if
                else {
                    logger.info("iidRow is not null:"+iidRow);
                }
            } // while more rows
            logger.info("SimpleDB6");
        } while (nextToken != null);
        logger.info("SimpleDB7");
    }

    private void addProperty(Item item) 
    {
        System.out.println("item=" + item.toString());
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
        System.out.println("item="+item.toString());
        Iterator<Attribute> attrs = item.getAttributes().iterator();

        String itemName = item.getName();
        System.out.println("itemName=" + itemName);
        dataSecurity.put(ITEMNAME, itemName);

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

        dataSecurity.put(KEYSTORE, ks);
        System.out.println("key="+KEYSTORE+",value="+ks);
        dataSecurity.put(TRUSTSTORE, ts);
        System.out.println("key=" + TRUSTSTORE + ",value=" + ts);
        dataSecurity.put(INSTANCE_ID, iid);
        System.out.println("key=" + INSTANCE_ID + ",value=" + iid);
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
/*
    private void download(AmazonS3Client s3Client, String path) throws BackupRestoreException
    {
        try
        {
            AbstractBackupPath newPath;
            long contentLen = s3Client.getObjectMetadata(S3BUCKET_PREFIX, path).getContentLength();
         //   RangeReadInputStream rris = new RangeReadInputStream(s3Client, S3BUCKET_PREFIX, path);
         //   compress.decompressAndClose(new BufferedInputStream(rris, (int)contentLen), os);
         //   bytesDownloaded.addAndGet(contentLen);

            RangeReadInputStream
        }
        catch (Exception e)
        {
            throw new BackupRestoreException(e.getMessage(), e);
        }
    }
    */
}
