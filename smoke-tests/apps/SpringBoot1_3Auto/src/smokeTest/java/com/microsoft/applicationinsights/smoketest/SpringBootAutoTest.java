/*
 * ApplicationInsights-Java
 * Copyright (c) Microsoft Corporation
 * All rights reserved.
 *
 * MIT License
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the ""Software""), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.microsoft.applicationinsights.smoketest;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runners.Parameterized;

@UseAgent
public class SpringBootAutoTest extends AiSmokeTest {

  // Spring Boot 1.3 does not support Java 11+
  @Parameterized.Parameters(name = "{index}: {0}, {1}, {2}")
  public static List<Object[]> parameterGenerator() {
    return Arrays.asList(
        new Object[] {"jetty9", "linux", "azul_zulu-openjdk_8"},
        new Object[] {"tomcat85", "linux", "azul_zulu-openjdk_8"},
        new Object[] {"wildfly11", "linux", "azul_zulu-openjdk_8"});
  }

  @Test
  @TargetUri("/test")
  public void doMostBasicTest() throws Exception {
    mockedIngestion.waitForItems("RequestData", 1);
  }
}
