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

import com.datastax.oss.driver.TestDataProviders;
import com.tngtech.java.junit.dataprovider.DataProvider;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DataProviderRunner.class)
public abstract class PagerTestBase {

  /**
   * The fetch size only matters for the async implementation. For sync this will essentially run
   * the same fixture 4 times, but that's not a problem because tests are fast.
   */
  @DataProvider
  public static Object[][] fetchSizes() {
    return TestDataProviders.fromList(1, 2, 3, 100);
  }

  @DataProvider
  public static Object[][] existingPageScenarios() {
    Object[][] fixtures =
        TestDataProviders.fromList(
            // ------- inputs -------- | ------ expected -------
            // iterable  | page | size | page | contents | last?
            "a,b,c,d,e,f | 1    | 3    | 1    | a,b,c    | false",
            "a,b,c,d,e,f | 2    | 3    | 2    | d,e,f    | true",
            "a,b,c,d,e,f | 2    | 4    | 2    | e,f      | true",
            "a,b,c,d,e,f | 2    | 5    | 2    | f        | true",
            "a,b,c       | 1    | 3    | 1    | a,b,c    | true",
            "a,b         | 1    | 3    | 1    | a,b      | true",
            "a           | 1    | 3    | 1    | a        | true",
            // first page of empty result set is always returned, even with FAIL strategy:
            "            | 1    | 3    | 1    |          | true");
    return TestDataProviders.combine(fixtures, fetchSizes());
  }

  @Test
  @UseDataProvider("existingPageScenarios")
  public void should_return_existing_page(String fixtureSpec, int fetchSize) {
    OffsetPagerTestFixture fixture = new OffsetPagerTestFixture(fixtureSpec);
    // Strategy should not matter here, test them all
    for (Pager.OutOfBoundsStrategy strategy : Pager.OutOfBoundsStrategy.values()) {
      Pager pager = new Pager(strategy);
      Pager.Page<String> actualPage = getActualPage(pager, fixture, fetchSize);
      fixture.assertMatches(actualPage);
    }
  }

  @DataProvider
  public static Object[][] failStrategyScenarios() {
    Object[][] fixtures =
        TestDataProviders.fromList(
            // ------------ inputs -------------
            // iterable  | page           | size
            "a,b,c       | 2              | 3",
            "a,b,c       | 10             | 3",
            "            | 2              | 3");
    return TestDataProviders.combine(fixtures, fetchSizes());
  }

  @Test
  @UseDataProvider("failStrategyScenarios")
  public void fail_strategy_should_throw_when_out_of_bounds(String fixtureSpec, int fetchSize) {
    OffsetPagerTestFixture fixture = new OffsetPagerTestFixture(fixtureSpec);
    Pager pager = new Pager(Pager.OutOfBoundsStrategy.FAIL);
    assertThrowsOutOfBounds(pager, fixture, fetchSize);
  }

  @DataProvider
  public static Object[][] lastPageStrategyScenarios() {
    Object[][] fixtures =
        TestDataProviders.fromList(
            // ------- inputs -------- | ------ expected -------
            // iterable  | page | size | page | contents | last?
            "a,b,c,d,e,f | 3    | 3    | 2    | d,e,f    | true",
            "a,b,c,d,e   | 3    | 3    | 2    | d,e      | true");
    return TestDataProviders.combine(fixtures, fetchSizes());
  }

  @Test
  @UseDataProvider("lastPageStrategyScenarios")
  public void last_page_strategy_should_return_last_page_when_out_of_bounds(
      String fixtureSpec, int fetchSize) {
    should_return_expected_page(fixtureSpec, fetchSize, Pager.OutOfBoundsStrategy.RETURN_LAST_PAGE);
  }

  @DataProvider
  public static Object[][] emptyPageStrategyScenarios() {
    Object[][] fixtures =
        TestDataProviders.fromList(
            // ------- inputs -------- | ------ expected -------
            // iterable  | page | size | page | contents | last?
            "a,b,c,d,e,f | 3    | 3    | 3    |          | true",
            "a,b,c,d,e,f | 10   | 3    | 10   |          | true");
    return TestDataProviders.combine(fixtures, fetchSizes());
  }

  @Test
  @UseDataProvider("emptyPageStrategyScenarios")
  public void empty_page_strategy_should_return_empty_page_when_out_of_bounds(
      String fixtureSpec, int fetchSize) {
    should_return_expected_page(
        fixtureSpec, fetchSize, Pager.OutOfBoundsStrategy.RETURN_EMPTY_PAGE);
  }

  private void should_return_expected_page(
      String fixtureSpec, int fetchSize, Pager.OutOfBoundsStrategy strategy) {
    OffsetPagerTestFixture fixture = new OffsetPagerTestFixture(fixtureSpec);
    Pager pager = new Pager(strategy);
    Pager.Page<String> actualPage = getActualPage(pager, fixture, fetchSize);
    fixture.assertMatches(actualPage);
  }

  protected abstract Pager.Page<String> getActualPage(
      Pager pager, OffsetPagerTestFixture fixture, int fetchSize);

  protected abstract void assertThrowsOutOfBounds(
      Pager pager, OffsetPagerTestFixture fixture, int fetchSize);
}
