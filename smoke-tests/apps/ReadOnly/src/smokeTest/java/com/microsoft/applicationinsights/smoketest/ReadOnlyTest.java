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

import static com.microsoft.applicationinsights.smoketest.WarEnvironmentValue.JAVA_11;
import static com.microsoft.applicationinsights.smoketest.WarEnvironmentValue.JAVA_11_OPENJ9;
import static com.microsoft.applicationinsights.smoketest.WarEnvironmentValue.JAVA_17;
import static com.microsoft.applicationinsights.smoketest.WarEnvironmentValue.JAVA_17_OPENJ9;
import static com.microsoft.applicationinsights.smoketest.WarEnvironmentValue.JAVA_18;
import static com.microsoft.applicationinsights.smoketest.WarEnvironmentValue.JAVA_18_OPENJ9;
import static com.microsoft.applicationinsights.smoketest.WarEnvironmentValue.JAVA_19;
import static com.microsoft.applicationinsights.smoketest.WarEnvironmentValue.JAVA_8;
import static com.microsoft.applicationinsights.smoketest.WarEnvironmentValue.JAVA_8_OPENJ9;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.applicationinsights.smoketest.schemav2.Data;
import com.microsoft.applicationinsights.smoketest.schemav2.Envelope;
import com.microsoft.applicationinsights.smoketest.schemav2.MessageData;
import com.microsoft.applicationinsights.smoketest.schemav2.SeverityLevel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@UseAgent
abstract class ReadOnlyTest {

  @RegisterExtension static final SmokeTestExtension testing = new SmokeTestExtension(true, true);

  @Test
  void test() throws Exception {
    List<Envelope> mdList = testing.mockedIngestion.waitForItems("MessageData", 1);

    Envelope mdEnvelope = mdList.get(0);

    assertThat(mdEnvelope.getSampleRate()).isNull();

    MessageData md = (MessageData) ((Data<?>) mdEnvelope.getData()).getBaseData();

    assertThat(md.getMessage()).isEqualTo("hello");
    assertThat(md.getSeverityLevel()).isEqualTo(SeverityLevel.INFORMATION);
    assertThat(md.getProperties()).containsEntry("SourceType", "Logger");
    assertThat(md.getProperties()).containsEntry("LoggerName", "smoketestapp");
    assertThat(md.getProperties()).containsKey("ThreadName");
    assertThat(md.getProperties()).hasSize(3);
  }

  @Environment(JAVA_8)
  static class Java8Test extends ReadOnlyTest {}

  @Environment(JAVA_8_OPENJ9)
  static class Java8OpenJ9Test extends ReadOnlyTest {}

  @Environment(JAVA_11)
  static class Java11Test extends ReadOnlyTest {}

  @Environment(JAVA_11_OPENJ9)
  static class Java11OpenJ9Test extends ReadOnlyTest {}

  @Environment(JAVA_17)
  static class Java17Test extends ReadOnlyTest {}

  @Environment(JAVA_17_OPENJ9)
  static class Java17OpenJ9Test extends ReadOnlyTest {}

  @Environment(JAVA_18)
  static class Java18Test extends ReadOnlyTest {}

  @Environment(JAVA_18_OPENJ9)
  static class Java18OpenJ9Test extends ReadOnlyTest {}

  @Environment(JAVA_19)
  static class Java19Test extends ReadOnlyTest {}
}