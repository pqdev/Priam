package com.netflix.priam.utils;

import com.netflix.priam.IConfiguration;
import com.netflix.priam.backup.BackupRestoreException;
import com.netflix.priam.backup.AbstractBackupPath;

/**
 * Created by jhuffman on 3/24/16.
 */
public class SSLFiles {

    private static String TRUSTSTORE = "truststore";
    private static String ITEMNAME = "itemName";
    private static String S3BUCKET_PREFIX = "xrs-support-prod/";

    public static void download(IConfiguration config) throws BackupRestoreException
    {
        try
        {
            System.out.println("SSLFiles keystore="+config.getKeystore());
            System.out.println("SSLFiles truststore="+config.getTruststore());
         //   AbstractBackupPath newPath;
         //   long contentLen = s3Client.getObjectMetadata(S3BUCKET_PREFIX, path).getContentLength();
         //   RangeReadInputStream rris = new RangeReadInputStream(s3Client, S3BUCKET_PREFIX, path);
         //   compress.decompressAndClose(new BufferedInputStream(rris, (int)contentLen), os);
         //   bytesDownloaded.addAndGet(contentLen);
        }
        catch (Exception e)
        {
            throw new BackupRestoreException(e.getMessage(), e);
        }
    }

}
