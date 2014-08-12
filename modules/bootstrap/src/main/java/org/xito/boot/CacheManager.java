// Copyright 2007 Xito.org
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.xito.boot;

import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.security.*;

import org.xito.boot.ui.DownloadProgressFrame;

/**
 * CacheManager is responsible for maintaining downloaded cached files and 
 * making sure the cache is up to date.
 * 
 * @author  Deane Richan
 */
public class CacheManager {
   
   //public static String PROTOCOL = "cache";
   private static Logger logger = Logger.getLogger(CacheManager.class.getName());
   private static SimpleDateFormat downloadDateFormat = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss");
      
   private DownloadProgressFrame downloadFrame;
   private File rootDir;
   
   private HashMap recentDownloads = new HashMap();
   //private HashMap connections = new HashMap();
   //private HashMap urls = new HashMap();
   private HashMap cacheFiles = new HashMap();
   private Properties cacheProperties = new Properties();   
      
   private boolean cache_disabled = false;
   private boolean calculate_progress = true;
   
   private static int DELAY = 0;
   private static int BUF_SIZE = 1024 * 10; //10k buffer
   private static String DELAY_PROPERTY = "boot.cache.delay";
   
   /** Creates a new instance of CacheManager */
   public CacheManager(File cacheDir, boolean disabled) {
      
      if(cacheDir.exists()) {
         if(cacheDir.isDirectory() == false) {
            throw new RuntimeException("Cache cannot be created because there is a file:"+
            cacheDir.toString()+ " which already exists.");
         }
      }
      else {
         if(cacheDir.mkdir()==false) {
            throw new RuntimeException("Cache: "+cacheDir.toString()+" cannot be created.");
         }
      }
      
      rootDir = cacheDir;
      cache_disabled = disabled;
      
      //Setup Timeout Settings
      //Defaults to 1 minute for connection and read timeouts
      System.setProperty("sun.net.client.defaultConnectTimeout", "60000");
      System.setProperty("sun.net.client.defaultReadTimeout", "60000");
      
      //Create default download frame
      if(!Boot.isHeadless()) {
         downloadFrame = new DownloadProgressFrame(this);
      }

      //Setup Delay for Debugging
      String delayValue = Boot.getBootProperty(DELAY_PROPERTY, "0");
      DELAY = Integer.parseInt(delayValue);

      
      readCacheProperties();
   }
   
   /**
    * Read Cache Settings from Props file
    */
   private void readCacheProperties() {
      try {
         File cachePropsFile = new File(rootDir, "cache.properties");
         if(cachePropsFile.exists()) {
            FileInputStream in = new FileInputStream(cachePropsFile);
            cacheProperties.load(in);
            in.close();
         }
      }
      catch(IOException ioExp) {
         logger.log(Level.WARNING, "Error Reading Cache Settings:"+ioExp.getMessage(), ioExp);
      }
      
      //Read in Download Frame Properties
      if(downloadFrame != null) {
         downloadFrame.setPreferredLocationAndSize();
      }
   }
   
   /**
    * Store Cache Settings to Props file
    */
   public void storeCacheProperties() {
      try {
         File cachePropsFile = new File(rootDir, "cache.properties");
                  
         FileOutputStream out = new FileOutputStream(cachePropsFile);
         cacheProperties.store(out, null);
         out.close();
      }
      catch(IOException ioExp) {
         logger.log(Level.WARNING, "Error Writing Cache Settings:"+ioExp.getMessage(), ioExp);
      }
   }
   
   /**
    * Return the Cache Settings that this CacheManager uses
    */
   public Properties getCacheProperties() {
      return cacheProperties;
   }
   
   /**
    * Convert a URL to a Cache URL. 
    */
   public URL convertToCachedURL(URL u) {
      
      try {
         return getCachedFileForURL(u).toURL();
      }
      catch(MalformedURLException badURL) {
         //this should never happen
         logger.log(Level.SEVERE, badURL.getMessage(), badURL);
      }
      
      return u;
   }
   
   /**
    * Convert from a Cache URL. 
    */
   public URL convertFromCachedURL(URL u) {
      if(u == null) return u;
      
      try {
         String urlStr = u.toString(); 
         String rootURLStr = this.rootDir.toURL().toString();
         //If the URL isn't a file protocol and the URL doesn't start with the Cache Dir then its not 
         //a Cache URL so just return it
         if(!u.getProtocol().equals("file") || !urlStr.startsWith(rootURLStr)) {
            return u;
         }
         
         //Check properties for download
         Properties infoProps = readInfo(u);
         String nonCacheURL = infoProps.getProperty("resource");
         if(nonCacheURL != null && !nonCacheURL.equals("")) {
            return new URL(nonCacheURL);
         }
         
         //Now convert it the manually way
         int s = rootURLStr.length();
         int e = urlStr.length();
         urlStr = urlStr.substring(s, e);
         s = 0;
         e = urlStr.indexOf('/', s);
         String protocol = urlStr.substring(s, e);
         s = e+1;
         e = urlStr.indexOf('/', s);
         String host = urlStr.substring(s, e);
         s = e+1;
         e = urlStr.indexOf('/', s);
         String port = urlStr.substring(s, e);
         s = e+1;
         e = urlStr.length();
         String file = urlStr.substring(s, e);
         if(file.endsWith("_root_")) {
            file = file.substring(0, file.lastIndexOf("/")+1);
         }
         
         return new URL(protocol, host, Integer.valueOf(port).intValue(), file);
      }
      catch(Exception badURL) {
         logger.log(Level.WARNING, "Can't convert Cache URL to a regular URL:"+u.toString(), badURL);
      }
            
      return u;
   }
   
   /**
    * Check to see if a cached file is up to date with the original resource by
    * Checking content length and size. If the resource's length and size is unknown
    * or it is different from the cached file this method will return false
    */
   public boolean isUptoDate(URL resource, CacheListener listener) {

      if (cache_disabled) return false;
      
      //We don't cache local file protocol resources so return true if its a file resource
      if(resource.getProtocol().equals("file")) {
         return true;
      }
      
      String name = null;
      try {
         //Check to see if the local cache file exists
         File cachedFile = getCachedFileForURL(resource);
         Properties infoProps = readInfo(resource);
         if(infoProps.isEmpty() || !cachedFile.exists()) {
            logger.info("UptoDate check failed Cached file doesn't exist:"+cachedFile.toString());
            return false;
         }
                  
         //Check to see if we just downloaded this thing
         Long lastDownloadTime = (Long)recentDownloads.get(resource);
         if(lastDownloadTime != null) {
            int RECENT = 300000; // 5 minutes
            if((System.currentTimeMillis() - lastDownloadTime.longValue()) < RECENT) {
               return true;
            }
         }
         
         //Check Policy
         CachePolicy policy = CachePolicy.getPolicy(infoProps.getProperty("cache-policy"));
         String completed_str = infoProps.getProperty("completed");
         boolean completed = (completed_str != null && completed_str.equals("true"))?true:false;
         Date lastDownload = null;
         String lastDownloadStr = infoProps.getProperty("last-downloaded");
         if(lastDownloadStr != null && !lastDownloadStr.equals("")) {
            try { 
               lastDownload = downloadDateFormat.parse(lastDownloadStr); 
            }
            catch(ParseException e) {
               lastDownload = null;
            }
            catch(NumberFormatException badNum) {
               lastDownload = null;
            }
         }
         
         //if the resource is downloaded and we are offline then return that we are uptodate
         if(completed && Boot.isOffline()) {
            return true;
         }
         
         //if the download was competed and the policy is not ALWAYS then check downloadDate against Policy
         if(completed && lastDownload != null && !policy.equals(CachePolicy.ALWAYS)) {
            long MILLIS_PER_DAY = 1000L * 60L * 60L * 24L;
            long MILLIS_PER_WEEK = MILLIS_PER_DAY * 7;
            long MILLIS_PER_MONTH = MILLIS_PER_DAY * 30;
            long dif = System.currentTimeMillis() - lastDownload.getTime();
            if(policy.equals(CachePolicy.DAILY) && dif < MILLIS_PER_DAY) {
               return true;
            }
            else if(policy.equals(CachePolicy.WEEKLY) && dif < MILLIS_PER_WEEK) {
               return true;
            }
            else if(policy.equals(CachePolicy.MONTHLY) && dif < MILLIS_PER_MONTH) {
               return true;
            }
         }
         //Are policy says we should check the resource for any updates         
         //Send Getting Info event to any listeners
         name = getDownloadName(resource);
         CacheEvent event = new CacheEvent(this, null, name, resource, -1, -1, -1, -1);
         if(listener != null) {
            listener.gettingInfo(event);
         }
         
         //Get File info from Server
         URLConnection conn = resource.openConnection();
         logger.finer("Getting UptoDate Info");
         long lastModified = conn.getLastModified();
         int size = conn.getContentLength();
         logger.finer("Done Getting UptoDate Info");
         
         if(cachedFile.lastModified() != lastModified) {
            logger.info("UptoDate check failed Last Modified Dates don't match:"+cachedFile.toString());
            return false;
         }
         if(cachedFile.length() != size) {
            logger.info("UptoDate check failed content sizes don't match:"+cachedFile.toString());
            return false;
         }
         
         //Guess we passed
         return true;
      }
      catch(IOException ioExp) {
         logger.log(Level.WARNING, "Error checking up to date:"+ioExp.getMessage(), ioExp);
      }
      finally {
         CacheEvent event = new CacheEvent(this, null, name, resource, -1, -1, -1, -1);
         if(listener != null) {
            listener.completeGettingInfo(event);
         }
      }
            
      return false;
   }
   
   /**
    * Cache a Resource and return its URL. If the cache is not up to date
    * then the content will be downloaded and cached and then the URL to the
    * cached resource will be returned
    * @param resource url of content to download
    * @param listener to be notified of download progress
    * @param policy to use for determining if something is up to date
    * @return URL of cached content
    * @throws IOException if there is a problem downloading the resource
    */
   public URL getResource(URL resource, CacheListener listener, CachePolicy policy) throws IOException {
            
      logger.fine("Getting Cached Content for:"+resource.toString());
      
      //First Download it if we have to 
      downloadResource(null, resource, listener, policy);
      File cachedFile = getCachedFileForURL(resource);
      return cachedFile.toURL();
   }
      
   /**
    * Get a local File object for a cached URL
    */
   public File getCachedFileForURL(final URL url) throws MalformedURLException {
      logger.fine("getCachedFileForURL:"+url);
      File f = null;
      
      //if the url starts with the cache dir then the url is pointing to the cache
      f = (File)AccessController.doPrivileged(new PrivilegedAction() {
         public Object run() {
            try {
               String rootDirStr = rootDir.toURL().toString();
               String urlStr = url.toString();
               if(urlStr.startsWith(rootDirStr)) {
                  return new File(url.getFile());
               }
            }
            catch(Exception e) {
               logger.log(Level.WARNING, e.toString(), e);
            }
            
            return null;
         }
      });
      if(f!=null) return f;
      
      //if the url is under the boot dir then we don't cache it but instead
      //return the actual file
      //This must be done in a Privileged Action so that untrusted code can ask for a resource
      f = (File)AccessController.doPrivileged(new PrivilegedAction() {
         public Object run() {
            try {
               String bootURI = Boot.getBootDir().toURI().toString();
               if(url.toString().startsWith(bootURI)) {
                  return new File(url.getFile());
               }
            }
            catch(Exception e) {
               //ignore this
            }
            
            return null;
         }
      });
      if(f != null) return f;
      
      //if the url is a file protocol URL then just return the actual file
      if(url.getProtocol().equals("file")) {
         f = new File(url.getFile());
      }
      if(f != null) return f;
      
      
      long start = System.currentTimeMillis();
      f = (File)cacheFiles.get(url.toString());
      if(f != null && f.exists()) return f;
      
      //Create Cached File structure
      String host = url.getHost();
      String protocol = url.getProtocol();
      int port = url.getPort();
      String path = url.getPath();
      String filePath = url.getFile();
      int lastSlash = path.lastIndexOf("/");
      String fileName = (lastSlash != -1) ? path.substring(lastSlash) : null;
      
      //use _root_ for filename if the URL does not point at a specific file
      if(fileName == null || fileName.equals("") || fileName.equals("/")) {
         fileName = "_root_";
      }
      
      //path should now be the directory names up to the filename
      if(lastSlash > -1) {
         path = path.substring(0, path.lastIndexOf("/"));
      }
      
      //Make Dir
      String dirName = null;
      if(protocol.equals("file")) {
         //remove : from file path
         if(System.getProperty("os.name").startsWith("Windows") && path.indexOf(':')>-1) {
            path = path.replace(':', '_');
         }
         dirName = protocol + "/" + host + "/" + path;
      }
      else {
         dirName = protocol + "/" + host + "/" + port + "/" + path;
      }
      
      final String newDirName = dirName;
      File dir = (File)AccessController.doPrivileged(new PrivilegedAction() {
         public Object run() {
            File dir = new File(rootDir, newDirName);
            if(dir.exists()==false) {
               dir.mkdirs();
            }
            return dir;
         }
      });
            
      f = new File(dir, fileName); 
      cacheFiles.put(url.toString(), f);
      logger.fine("Cache File:"+f.toString());
      
      return f;
   }
  
   /**
    * The default Listener for the cache manager
    */
   public CacheListener getDefaultListener() {
            
      return downloadFrame;
   }

   /**
    * Download a single Resource Resource in this Thread. Uses a CachePolicy of ALWAYS
    *
    * @param url to download
    * @param listener to notify
    */
   public void downloadResource(URL url, CacheListener listener) throws IOException  {
      downloadResource(null, url, listener, CachePolicy.ALWAYS);
   }

   /**
    * Download a single Resource Resource in this Thread. Uses a CachePolicy of ALWAYS
    *
    * @param resourceGroupName group name used to group downloads used by listener
    * @param url to download
    * @param listener to notify
    */
   public void downloadResource(String resourceGroupName, URL url, CacheListener listener) throws IOException  {
      downloadResource(resourceGroupName, url, listener, CachePolicy.ALWAYS);
   }

   /**
    * Download a single Resource Resource in this Thread
    */
   public void downloadResource(String resourceGroupName, final URL url, CacheListener listener, CachePolicy policy) throws IOException  {

      //if the url is under the boot dir then we don't cache it 
      //This must be done in a Privileged Action so that untrusted code can ask for a resource
      Boolean inBootDir = (Boolean)AccessController.doPrivileged(new PrivilegedAction() {
         public Object run() {
            String bootURI = Boot.getBootDir().toURI().toString();
            return new Boolean(url.toString().startsWith(bootURI));
         }
      });
      if(inBootDir.booleanValue()) {
         return;
      }
      
      //Check to see if it is up to Date
      if(isUptoDate(url, listener)) return;
      
      //Now actually Dowload the Resource
      download(resourceGroupName, url, listener, policy);
   }
   
   /**
    * Download the contents of the URL to the local Cache
    * @param resourceGroupName name that download is associated with
    * @param url to download
    * @param listener to notify
    * @param policy to use. If null uses CachePolicy.ALWAYS
    * @throws IOException if there is a problem downloading the resource
    */
   private void download(String resourceGroupName, URL url, CacheListener listener, CachePolicy policy) throws IOException {

      //we don't download and cache file resources
      if(url.getProtocol().equals("file")) {
         return;
      }

      long start = System.currentTimeMillis();
      logger.info("Downloading Resource: "+url.toString());
      String name = getDownloadName(url);
      CacheEvent event = new CacheEvent(this, resourceGroupName, name, url, -1, -1, -1, -1);
      
      //Send Start Download Event
      if(listener != null) {
         listener.startDownload(event);
      }
      
      if(policy == null) {
         policy = CachePolicy.ALWAYS;
      }

      File file = null;
      try {
         URLConnection conn = url.openConnection();
         
         logger.finer("Getting Download Stream: "+url.toString());
         InputStream in = conn.getInputStream();
         long lastModified = conn.getLastModified();
         int size = conn.getContentLength();
         logger.finer("Done Getting Download Stream: "+url.toString());

         if(!calculate_progress) size = -1;

         int progressSize = 0;
         long progressTime = 0;
         long estimateTime = 0;
         long startTime = System.currentTimeMillis();

         file = getCachedFileForURL(url);
         FileOutputStream out = new FileOutputStream(file);

         byte[] buf = new byte[BUF_SIZE];
         int count = in.read(buf);

         while(count > 0) {
            out.write(buf, 0, count);
            progressSize += count;
            progressTime = System.currentTimeMillis() - startTime;
            if(size != -1 && progressSize < size) {
               estimateTime = Math.round(((1.0/(progressSize/(double)size))* (double)progressTime) - progressTime);
            }
            else {
               estimateTime = -1;
            }

            event = new CacheEvent(this, resourceGroupName, name, url, size, progressSize, progressTime, estimateTime);
            if(listener != null) {
               listener.updateDownload(event);
            }
            
            //Delay for debugging purposes
            if(DELAY >0) {
               try {
                  Thread.sleep(DELAY);
               }
               catch(Exception e) {
                  logger.severe(e.getMessage());
               }
            }

            count = in.read(buf);
         }

         in.close();
         out.close();
         recentDownloads.put(url, new Long(System.currentTimeMillis()));
         writeInfo(url, file, policy, true);
         
         //update last modified time
         if(lastModified != 0) {
            file.setLastModified(lastModified);
         }

         estimateTime = 0;
         event = new CacheEvent(this, resourceGroupName, name, url, size, progressSize, progressTime, estimateTime);
         if(listener != null) {
            listener.completeDownload(event);
         }

         logger.info("Downloading Complete:"+url.toString());
         logger.fine("Download Time:"+(System.currentTimeMillis()-start));
      }
      catch(IOException ioExp) {
         logger.log(Level.WARNING, ioExp.getMessage(), ioExp);
         writeInfo(url, file, policy, false);
         
         //Send download Error
         if(listener != null) {
            listener.downloadException(name, url, ioExp.getMessage(), ioExp);
         }
         
         //Now throw the error up
         throw ioExp;
      }
   }
   
   /**
    * Write out the information file about this cached Resource
    */
   protected void writeInfo(URL url, File file, CachePolicy policy, boolean completed) {
      if(file == null) return;
      File infoFile = new File(file.getAbsolutePath() + ".info");
      
      Properties props = new Properties();
      props.setProperty("resource", url.toString());
      props.setProperty("cache-file", file.toString());
      props.setProperty("last-downloaded", downloadDateFormat.format(new Date()));
      props.setProperty("cache-policy", policy.toString());
      props.setProperty("completed", Boolean.toString(completed));
      
      FileOutputStream out = null; 
      try {
         out = new FileOutputStream(infoFile);
         props.store(out, null);
      }
      catch(IOException ioExp) {
         logger.log(Level.WARNING, ioExp.getMessage(), ioExp);
      }
      finally {
         if(out != null) {
            try{out.close();}catch(IOException ioExp) {ioExp.printStackTrace();}
         }
      }
   }
   
   protected Properties readInfo(URL url) {
      Properties props = new Properties();
      FileInputStream in = null;
      try {
         File cacheFile = this.getCachedFileForURL(url);
         if(cacheFile.exists() == false)
            return props;
         
         File infoFile = new File(cacheFile.getAbsolutePath() + ".info");
         if(infoFile.exists() == false)
            return props;
         
         in = new FileInputStream(infoFile);
         props.load(in);
      }
      catch(IOException ioExp) {
         logger.log(Level.WARNING, ioExp.getMessage(), ioExp);
      }
      finally {
         if(in != null) {
            try{in.close();}catch(IOException ioExp) {ioExp.printStackTrace();}
         }
      }
      
      return props;
   }
   
   /**
    * Get a descriptive name for this download URL
    */
   protected String getDownloadName(URL u) {
      String path = u.getPath();
      String fileName = path.substring(path.lastIndexOf("/")+1);
      return fileName;
   }
   
   /**
    * Download a single resource in a background Thread
    * @param resourceGroupName a name used to group downloads together
    * @param url to download
    * @param listener that should get download events
    * @param policy to use for caching
    * @param g ThreadGroup to create download thread in.
    */
   public Thread downloadResourceInBackground(final String resourceGroupName, final URL url, final CacheListener listener, final CachePolicy policy, ThreadGroup g) {
   
      Thread t = new Thread(g, new Runnable() {
         public void run() {
            try {
               downloadResource(resourceGroupName, url, listener, policy);
            }
            catch(Exception exp) {
               logger.log(Level.WARNING, exp.getMessage()+":"+url.toString(), exp);
               if(listener != null) {
                  listener.downloadException(getDownloadName(url), url, exp.getMessage(), exp);
               }
            }
         }
      });
          
      t.start();
      return t;
   }

   /**
    * Download multiple resources
    *
    * @param execDesc optional executableDesc that is requesting these resources can be null
    * @param resources to download
    * @param listener to be notified
    * @param atSameTime true if all URLs are to be fetched at the same time or false if one at a time
    */
   public void downloadResources(ExecutableDesc execDesc, List<CacheResource> resources, CacheListener listener, boolean atSameTime) {
      downloadResources( (execDesc != null) ? execDesc.toString() : null, resources, listener, atSameTime);
   }

   /**
    * Download multiple resources
    *
    * @param descriptiveName optional name of set of resources. can be null
    * @param resources to download
    * @param listener to be notified
    * @param atSameTime true if all URLs are to be fetched at the same time or false if one at a time
    */
   public void downloadResources(final String descriptiveName, final List<CacheResource> resources, final CacheListener listener, final boolean atSameTime) {

      ThreadGroup g =(ThreadGroup)AccessController.doPrivileged(new PrivilegedAction() {
         public Object run() {
            return new ThreadGroup("CacheManager: downloadResources");
         }
      });

      //Download each url in its own thread
      if(atSameTime) {

         for(CacheResource resource : resources){
            downloadResourceInBackground(descriptiveName, resource.getUrl(), listener, resource.getCachePolicy(), g);
         }

         try {
            while(g.activeCount()>0) {
               Thread.sleep(500);
               Thread.yield();
            }
         }
         catch(Exception e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
         }
      }
      //Download one at a time
      else {
         Thread t = new Thread(g, new Runnable() {
            public void run() {

               for(CacheResource resource: resources){
                  try {
                     downloadResource(descriptiveName, resource.getUrl(), listener, resource.getCachePolicy());
                  }
                  catch(Exception exp) {
                     logger.log(Level.WARNING, exp.getMessage(), exp);
                     if(listener != null) {
                        URL url = resource.getUrl();
                        listener.downloadException(getDownloadName(url), url, exp.getMessage(), exp);
                     }
                  }
               }
            }
         });

         t.start();
         try {
            t.join();
         }
         catch(InterruptedException exp) {
            logger.log(Level.SEVERE, exp.getMessage(), exp);
         }
      }
   }

   /**
    * Download multiple resources in background Thread or Threads
    *
    * @param execDesc optional ExecutableDesc that is requesting these resources
    * @param resources to download
    * @param listener to be notified
    * @param atSameTime true if all URLs are to be fetched at the same time or false if one at a time
    */
   public Thread downloadResourcesInBackground(final ExecutableDesc execDesc, final List<CacheResource> resources, final CacheListener listener, final boolean atSameTime) {
               
      //Download in BackGround
      Thread t = new Thread(new Runnable() {
         public void run() {
            downloadResources(execDesc, resources, listener, atSameTime);
         }
      });
          
      t.start();
      return t;
   }
   
   /**
    * Clears the Entire Local Cache
    * @return true if the whole cache could be cleared false other wise
    */
   public boolean clearCache() {
      return clearCacheDir(rootDir, false);
   }
   
   /**
    * Clears the Cache of a list of Resources
    * @param resources urls to clear from cache
    * @return true if the resources were cleared from the cache
    */
   public boolean clearCache(URL[] resources) {
      if(resources == null) return true;
      
      boolean failed = false;
      for (URL resource : resources) {
         if (!clearCache(resource) && !failed) {
            failed = true;
         }
      }
      
      return failed;
   }
   
   /**
    * Clears the Cache of a single Resource
    * @param resource to clear from cache
    * @return true if the resource was cleared from the cache
    */
   public boolean clearCache(URL resource) {
      try {
         File f = getCachedFileForURL(resource);
         File infoF = new File(f.getAbsoluteFile()+".info");
         if(!f.exists()) {
            return true;
         }

         if(f.isFile()) {
            boolean success = infoF.delete();
            if(success) {
               return f.delete();
            }
            else {
               throw new IOException("could not delete: " + infoF.getAbsolutePath());
            }
         }
         else {
            return clearCacheDir(f, true);
         }
      }
      catch(IOException ioExp) {
         logger.log(Level.WARNING, ioExp.getMessage(), ioExp);
         return false;
      }
   }
   
   /**
    * Clears an Entire directory in the Cache
    */
   private boolean clearCacheDir(File dir, boolean deleteDir) {
      if(dir == null) return true;
      if(dir.exists() == false) return true;
      
      //if its a file. It shouldn't be but if it is just delete it
      if(dir.isFile()) {
         return dir.delete();
      }
      
      //Now recursively delete all contents
      File[] children = dir.listFiles();
      boolean failed = false;
      for(int i=0;i<children.length;i++) {
         if(children[i].isFile()) {
            if(children[i].delete()==false && failed==false) {
               failed = true;
            }
         }
         else {
            if(clearCacheDir(children[i], true) == false && failed == false) {
               failed = true;
            }
         }
      }
      if(failed) {
         return failed;
      }
      
      //We deleted all the children so now delete this directory
      if(deleteDir)
         return dir.delete();
      else
         return true;
   }
}
