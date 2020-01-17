/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.api.core.paging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PagerSyncTest extends PagerTestBase {

  @Override
  protected Pager.Page<String> getActualPage(
      Pager pager, OffsetPagerTestFixture fixture, /*ignored*/ int fetchSize) {
    return pager.getPage(
        fixture.getSyncIterable(), fixture.getRequestedPage(), fixture.getPageSize());
  }

  @Override
  protected void assertThrowsOutOfBounds(
      Pager pager, OffsetPagerTestFixture fixture, int fetchSize) {
    assertThatThrownBy(() -> getActualPage(pager, fixture, fetchSize))
        .isInstanceOf(IndexOutOfBoundsException.class);
  }
}
