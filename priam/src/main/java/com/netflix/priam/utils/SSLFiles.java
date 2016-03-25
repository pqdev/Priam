package com.netflix.priam.utils;

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.netflix.priam.IConfiguration;
import com.netflix.priam.ICredential;
import com.netflix.priam.backup.BackupRestoreException;
import org.apache.commons.io.IOUtils;
import org.xerial.snappy.SnappyInputStream;

import java.io.*;

/**
 * Created by jhuffman on 3/24/16.
 */
public class SSLFiles {

    private static String TRUSTSTORE = "truststore";
    private static String ITEMNAME = "itemName";
    private static final int BUFFER = 16 * 1024;
    private static String KEYSTORE_PATH = "/etc/cassandra/conf/.keystore";
    private static String TRUSTSTORE_PATH = "/etc/cassandra/conf/.truststore";

    public static void download(IConfiguration config) throws BackupRestoreException
    {
        try
        {
            System.out.println("SSLFiles keystore="+config.getKeystore());
            System.out.println("SSLFiles truststore=" + config.getTruststore());

            int i = config.getKeystore().indexOf("/");
            System.out.println("i="+i);
            String bucket = config.getKeystore().substring(0, i);
            String pathKeystore   = config.getKeystore().substring(i+1);
            i = config.getTruststore().indexOf("/");
            String pathTruststore = config.getTruststore().substring(i+1);

            System.out.println("SSLFiles bucket="+ bucket);
            System.out.println("SSLFiles pathKeystore=" + pathKeystore);
            System.out.println("SSLFiles pathTruststore=" + pathTruststore);

            ICredential cred = config.getCredential();
            AmazonS3Client client = new AmazonS3Client(cred.getAwsCredentialProvider());
            client.setEndpoint(config.getS3EndPoint());

            GetObjectRequest req = new GetObjectRequest(bucket, pathKeystore);
            S3ObjectInputStream is = client.getObject(req).getObjectContent();
            OutputStream os = new FileOutputStream(KEYSTORE_PATH);
            writeit(is, os);

            GetObjectRequest req2 = new GetObjectRequest(bucket, pathTruststore);
            S3ObjectInputStream is2 = client.getObject(req2).getObjectContent();
            OutputStream os2 = new FileOutputStream(TRUSTSTORE_PATH);
            writeit(is2, os2);
        }
        catch (Exception e)
        {
            throw new BackupRestoreException(e.getMessage(), e);
        }
    }

    private static void writeit(InputStream input, OutputStream output) throws IOException
    {
        SnappyInputStream is = new SnappyInputStream(new BufferedInputStream(input));
        byte data[] = new byte[BUFFER];
        BufferedOutputStream dest1 = new BufferedOutputStream(output, BUFFER);
        try
        {
            int c;
            while ((c = is.read(data, 0, BUFFER)) != -1)
            {
                dest1.write(data, 0, c);
            }
        }
        finally
        {
            IOUtils.closeQuietly(dest1);
            IOUtils.closeQuietly(is);
        }
    }

}
