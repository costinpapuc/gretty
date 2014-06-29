/*
 * gretty
 *
 * Copyright 2013  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.gretty

/**
 *
 * @author akhikhl
 */
interface Launcher {
  
  ScannerManager getScannerManager()

  void launch()
  
  void setScannerManager(ScannerManager newValue)
}