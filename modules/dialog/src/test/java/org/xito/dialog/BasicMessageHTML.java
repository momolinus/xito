
package org.xito.dialog;

public class BasicMessageHTML {
   
   public static void main(String args[]) {
      
      String html = "<html>This is a test of some <b>HTML</b> Message org.xito: <ol><li>test1</li><li>test2<li</ol></html>";
      DialogManager.showMessage(null, "Basic Message Title", html);
   }
   
}
