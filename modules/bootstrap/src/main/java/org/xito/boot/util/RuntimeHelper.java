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

package org.xito.boot.util;

import java.io.*;

/**
 * This class wraps Runtime.exec methods making it easy to run seperate processes and work with process streams
 * @author Deane Richan
 */
public class RuntimeHelper {
   
   /**
    * Exec a Command. This method will Create interal Streams to Handle the execution
    *
    * @param cmd to execute
    * @param args to pass
    * @param env variables
    * @param curDir
    */
   public static Process exec(String cmd, String args, String[] env, File curDir) throws IOException {
      
      String[] cmds = null;
      if(args == null) {
         cmds = new String[]{cmd};
      }
      else {
         String[] argsArray = args.split(" ");
         cmds = new String[argsArray.length+1];
         cmds[0] = cmd;
         for(int i=0;i<argsArray.length;i++)
            cmds[i+1] = argsArray[i];
      }
      
      return exec(cmds, env, curDir);
   }  

   /**
    * Exec a Command
    *
    * @param cmds to execute the first string is the command the rest are arguments
    * @param env variables
    * @param curDir
    */
   public static Process exec(String[] cmds, String[] env, File curDir) throws IOException {
      
      return exec(cmds, env, curDir, null, null, null);
   }  
   
   /**
    *
    * If the streams are not specified Stream Gobblers will be created.
    * The streams are read in seperate Threads generated by this method
    *
    * @param cmds to execute
    * @param env variables
    * @param curDir
    * @param outStream - The Stream you want the process's out sent to
    * @param errStream - The Stream you want the process's err sent to
    * @param inStream - The Stream that should be read for input to the proces
    */
   public static Process exec(String[] cmds, String[] env, File curDir, 
      OutputStream outStream, OutputStream errStream, InputStream inStream) throws IOException {
      
      Process p = Runtime.getRuntime().exec(cmds, env, curDir);
      handleStreams(p, outStream, errStream, inStream);
      return p;
   }  
   
   private static void handleStreams(final Process p, final OutputStream outStream, final OutputStream errStream, final InputStream inStream) {
   
      //Handle Output
      Thread outThread = new Thread(new Runnable() {
         public void run() {
            try {
               InputStream pOut = p.getInputStream();
               byte buf[] = new byte[64];
               int c = pOut.read(buf);
               while(c != -1) {
                  if(outStream != null) {
                     outStream.write(buf, 0, c);
                  }
                  c = pOut.read(buf);
               }
            }
            catch(IOException ioExp) {
               ioExp.printStackTrace();
            }
         } 
      });
      
      //Handle Error
      Thread errThread = new Thread(new Runnable() {
         public void run() {
            try {
               InputStream pErr = p.getErrorStream();
               byte buf[] = new byte[64];
               int c = pErr.read(buf);
               while(c != -1) {
                  if(errStream != null) {
                     errStream.write(buf, 0, c);
                  }
                  c = pErr.read(buf);
               }
            }
            catch(IOException ioExp) {
               ioExp.printStackTrace();
            }
         }
      });
      
      //Handle Input
      Thread inThread = new Thread(new Runnable() {
         public void run() {
            try {
               if(inStream == null) return;
               OutputStream pOut = p.getOutputStream();
               byte buf[] = new byte[64];
               int c = inStream.read(buf);
               while(c != -1) {
                  pOut.write(buf, 0, c);
                  c = inStream.read(buf);
               }
            }
            catch(IOException ioExp) {
               ioExp.printStackTrace();
            }
         }
      });
      
      outThread.setDaemon(true);
      errThread.setDaemon(true);
      inThread.setDaemon(true);
      
      outThread.start();
      errThread.start();
      inThread.start();
   }
}
