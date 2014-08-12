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

package org.xito.dialog;

import java.awt.*;
import javax.swing.*;

/**
 *
 * @author Deane
 */
public class DialogUnitConverter {
    
    static float pw;
    static float ph;
    
    /** Creates a new instance of DialogUnitConverter */
    public DialogUnitConverter() {
    }
    
    public static void initialize() {
        
        JLabel lbl = new JLabel();
        Font f = lbl.getFont();
        FontMetrics fm = lbl.getFontMetrics(f);
        int h = fm.getHeight();
        int w = 0;
        int wa[] = fm.getWidths();
        int sum = 0;
        for(int i=0;i<wa.length;i++) {
            sum = sum + wa[i];
        }
        w = sum / wa.length;
        
        pw = w / 4f;
        ph = h / 8f;
        
        System.out.println(pw);
        System.out.println(ph);
        
    }
    
    public static int getHorzPixelsForUnits(int units) {
        return (int)(pw*(float)units);
    }
    
    public static int getVertPixelsForUnits(int units) {
        return (int)(ph*(float)units);
    }
    
    public static void main(String args[]) {
        
        DialogUnitConverter.initialize();
        
        System.out.println("10:"+getHorzPixelsForUnits(10));
        System.out.println("10:"+getVertPixelsForUnits(10));
        
    }
    
}
