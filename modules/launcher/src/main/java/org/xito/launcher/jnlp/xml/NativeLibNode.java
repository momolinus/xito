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

package org.xito.launcher.jnlp.xml;

import java.text.*;
import java.net.*;
import java.util.logging.*;
import org.w3c.dom.*;

import org.xito.launcher.Resources;

/**
 *
 * @author DRICHAN
 */
public class NativeLibNode extends AbstractResourceNode {
   
   private static final Logger logger = Logger.getLogger(IconDescNode.class.getName());
   
   public static final String NAME = "nativelib";
      
   /** Creates a new instance of IconDescNode */
   public NativeLibNode(URL codebaseURL, Element node) throws InvalidJNLPException {
      if(!node.getNodeName().equals(NAME)) {
         String msg = Resources.jnlpBundle.getString("jnlp.xml.bad.element");
         throw new InvalidJNLPException(MessageFormat.format(msg, NAME));
      }
      super.processNode(codebaseURL, node);
   }
}
