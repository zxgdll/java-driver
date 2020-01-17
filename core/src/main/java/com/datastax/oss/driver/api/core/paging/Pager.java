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

import com.datastax.oss.driver.api.core.AsyncPagingIterable;
import com.datastax.oss.driver.api.core.PagingIterable;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class Pager {

  /** A page returned as the result of an offset query. */
  public interface Page<ElementT> {

    /** The elements in the page. */
    @NonNull
    List<ElementT> getElements();

    /**
     * The page number.
     *
     * <p>It will usually match the requested page number, except if the requested page was past the
     * end of the iterable, and the out of bounds strategy is {@link
     * OutOfBoundsStrategy#RETURN_LAST_PAGE}.
     */
    int getPageNumber();

    /** Whether there are other pages after this one. */
    boolean isLast();
  }

  /** What to do if the caller requests a page that is past the end of the current result set. */
  public enum OutOfBoundsStrategy {
    /**
     * Fail with an {@link IndexOutOfBoundsException}.
     *
     * <p>As a special case, if the result set is empty and the first page is requested, this
     * strategy will return an empty page, not fail.
     */
    FAIL,

    /**
     * Return the last non-empty page of results, instead of the requested page. Note that its
     * {@link Page#getPageNumber() page number} will be different than the requested one.
     */
    RETURN_LAST_PAGE,

    /** Return a page with the correct page number, but no elements. */
    RETURN_EMPTY_PAGE,
  }

  private final OutOfBoundsStrategy outOfBoundsStrategy;

  /**
   * Creates a new instance.
   *
   * @param outOfBoundsStrategy the strategy to use if the caller requests a page that is past the
   *     end of the current result set.
   */
  public Pager(@NonNull OutOfBoundsStrategy outOfBoundsStrategy) {
    this.outOfBoundsStrategy = Objects.requireNonNull(outOfBoundsStrategy);
  }

  /**
   * Skips the beginning of a set of results to return the given page, assuming the given page size.
   *
   * @param iterable the iterable to extract the results from.
   * @param targetPageNumber the page to return (1 for the first page, 2 for the second page, etc).
   *     Must be greater than or equal to 1.
   * @param pageSize the number of elements per page. Must be greater than or equal to 1.
   * @throws IllegalArgumentException if the conditions on the arguments are not respected.
   * @throws IndexOutOfBoundsException if the requested page is past the end of the iterable, and
   *     the out of bounds strategy is {@link OutOfBoundsStrategy#FAIL}.
   */
  @NonNull
  public <ElementT> Page<ElementT> getPage(
      @NonNull PagingIterable<ElementT> iterable, final int targetPageNumber, final int pageSize) {

    Objects.requireNonNull(iterable);
    if (targetPageNumber < 1) {
      throw new IllegalArgumentException(
          "Invalid targetPageNumber, expected >=1, got " + targetPageNumber);
    }
    if (pageSize < 1) {
      throw new IllegalArgumentException("Invalid pageSize, expected >=1, got " + pageSize);
    }

    // Holds the contents of the target page. If the strategy is RETURN_LAST_PAGE, we also need to
    // record the current page as we go, because our iterable is forward-only and we can't predict
    // when we'll hit the end.
    List<ElementT> currentPageElements = new ArrayList<>();

    int currentPageNumber = 1;
    int currentPageSize = 0;
    for (ElementT element : iterable) {
      currentPageSize += 1;

      if (currentPageSize > pageSize) {
        currentPageNumber += 1;
        currentPageSize = 1;
        if (outOfBoundsStrategy == OutOfBoundsStrategy.RETURN_LAST_PAGE) {
          currentPageElements.clear();
        }
      }

      if (currentPageNumber == targetPageNumber
          || outOfBoundsStrategy == OutOfBoundsStrategy.RETURN_LAST_PAGE) {
        currentPageElements.add(element);
      }

      if (currentPageNumber == targetPageNumber && currentPageSize == pageSize) {
        // The target page has the full size and we've seen all of its elements
        break;
      }
    }

    // Either we have the full target page, or we've reached the end of the result set.
    if (currentPageNumber == targetPageNumber
        || outOfBoundsStrategy == OutOfBoundsStrategy.RETURN_LAST_PAGE) {
      boolean isLast = iterable.one() == null;
      return new DefaultPage<>(currentPageElements, currentPageNumber, isLast);
    } else if (outOfBoundsStrategy == OutOfBoundsStrategy.RETURN_EMPTY_PAGE) {
      return new DefaultPage<>(Collections.emptyList(), targetPageNumber, true);
    } else if (outOfBoundsStrategy == OutOfBoundsStrategy.FAIL) {
      int totalCount = (currentPageNumber - 1) * pageSize + currentPageSize;
      throw new IndexOutOfBoundsException(
          String.format(
              "Page %d out of bounds. The result only contains %d elements "
                  + "(%d pages of %d elements)",
              targetPageNumber, totalCount, currentPageNumber, pageSize));
    } else {
      throw new AssertionError("Unknown strategy: " + outOfBoundsStrategy);
    }
  }

  /**
   * Skips the beginning of a set of results to return the given page, assuming the given page size.
   *
   * @param iterable the iterable to extract the results from.
   * @param targetPageNumber the page to return (1 for the first page, 2 for the second page, etc).
   *     Must be greater than or equal to 1.
   * @param pageSize the number of elements per page. Must be greater than or equal to 1.
   * @return a stage that will complete with the requested page, or fail with
   *     IndexOutOfBoundsException if the requested page is past the end of the iterable, and the
   *     out of bounds strategy is {@link OutOfBoundsStrategy#FAIL}.
   * @throws IllegalArgumentException if the conditions on the arguments are not respected.
   */
  @NonNull
  public <ElementT, IterableT extends AsyncPagingIterable<ElementT, IterableT>>
      CompletionStage<Page<ElementT>> getPage(
          @NonNull IterableT iterable, final int targetPageNumber, final int pageSize) {

    // Throw IllegalArgumentException directly instead of failing the stage, since it signals
    // blatant programming errors
    Objects.requireNonNull(iterable);
    if (targetPageNumber < 1) {
      throw new IllegalArgumentException(
          "Invalid targetPageNumber, expected >=1, got " + targetPageNumber);
    }
    if (pageSize < 1) {
      throw new IllegalArgumentException("Invalid pageSize, expected >=1, got " + pageSize);
    }

    CompletableFuture<Page<ElementT>> pageFuture = new CompletableFuture<>();
    getPage(iterable, targetPageNumber, pageSize, 1, 0, new ArrayList<>(), pageFuture);

    return pageFuture;
  }

  /**
   * Main method for the async iteration.
   *
   * <p>See the synchronous version in {@link #getPage(PagingIterable, int, int)} for more
   * explanations: this is identical, except that it is async and we need to handle protocol page
   * transitions manually.
   */
  private <IterableT extends AsyncPagingIterable<ElementT, IterableT>, ElementT> void getPage(
      IterableT iterable,
      final int targetPageNumber,
      final int pageSize,
      int currentPageNumber,
      int currentPageSize,
      List<ElementT> currentPageElements,
      CompletableFuture<Page<ElementT>> pageFuture) {

    // Note: iterable.currentPage()/fetchNextPage() refer to protocol-level pages, do not confuse
    // with logical pages handled by this class
    Iterator<ElementT> currentFrame = iterable.currentPage().iterator();
    while (currentFrame.hasNext()) {
      ElementT element = currentFrame.next();

      currentPageSize += 1;

      if (currentPageSize > pageSize) {
        currentPageNumber += 1;
        currentPageSize = 1;
        if (outOfBoundsStrategy == OutOfBoundsStrategy.RETURN_LAST_PAGE) {
          currentPageElements.clear();
        }
      }

      if (currentPageNumber == targetPageNumber
          || outOfBoundsStrategy == OutOfBoundsStrategy.RETURN_LAST_PAGE) {
        currentPageElements.add(element);
      }

      if (currentPageNumber == targetPageNumber && currentPageSize == pageSize) {
        // Full-size target page. In this method it's simpler to finish directly here.
        if (currentFrame.hasNext()) {
          pageFuture.complete(new DefaultPage<>(currentPageElements, currentPageNumber, false));
        } else if (!iterable.hasMorePages()) {
          pageFuture.complete(new DefaultPage<>(currentPageElements, currentPageNumber, true));
        } else {
          // It's possible for the server to return an empty last frame, so we need to fetch it to
          // know for sure whether there are more elements
          int finalCurrentPageNumber = currentPageNumber;
          iterable
              .fetchNextPage()
              .whenComplete(
                  (nextIterable, throwable) -> {
                    if (throwable != null) {
                      pageFuture.completeExceptionally(throwable);
                    } else {
                      boolean isLastPage = !nextIterable.currentPage().iterator().hasNext();
                      pageFuture.complete(
                          new DefaultPage<>(
                              currentPageElements, finalCurrentPageNumber, isLastPage));
                    }
                  });
        }
        return;
      }
    }

    if (iterable.hasMorePages()) {
      int finalCurrentPageNumber = currentPageNumber;
      int finalCurrentPageSize = currentPageSize;
      iterable
          .fetchNextPage()
          .whenComplete(
              (nextIterable, throwable) -> {
                if (throwable != null) {
                  pageFuture.completeExceptionally(throwable);
                } else {
                  getPage(
                      nextIterable,
                      targetPageNumber,
                      pageSize,
                      finalCurrentPageNumber,
                      finalCurrentPageSize,
                      currentPageElements,
                      pageFuture);
                }
              });
    } else {
      // Reached the end of the result set, finish with what we have so far
      if (currentPageNumber == targetPageNumber
          || outOfBoundsStrategy == OutOfBoundsStrategy.RETURN_LAST_PAGE) {
        pageFuture.complete(new DefaultPage<>(currentPageElements, currentPageNumber, true));
      } else if (outOfBoundsStrategy == OutOfBoundsStrategy.RETURN_EMPTY_PAGE) {
        pageFuture.complete(new DefaultPage<>(Collections.emptyList(), targetPageNumber, true));
      } else if (outOfBoundsStrategy == OutOfBoundsStrategy.FAIL) {
        int totalCount = (currentPageNumber - 1) * pageSize + currentPageSize;
        pageFuture.completeExceptionally(
            new IndexOutOfBoundsException(
                String.format(
                    "Page %d out of bounds. The result only contains %d elements "
                        + "(%d pages of %d elements)",
                    targetPageNumber, totalCount, currentPageNumber, pageSize)));
      } else {
        pageFuture.completeExceptionally(
            new AssertionError("Unknown strategy: " + outOfBoundsStrategy));
      }
    }
  }

  private static class DefaultPage<ElementT> implements Page<ElementT> {
    private final List<ElementT> elements;
    private final int pageNumber;
    private final boolean isLast;

    DefaultPage(@NonNull List<ElementT> elements, int pageNumber, boolean isLast) {
      this.elements = ImmutableList.copyOf(elements);
      this.pageNumber = pageNumber;
      this.isLast = isLast;
    }

    @NonNull
    @Override
    public List<ElementT> getElements() {
      return elements;
    }

    @Override
    public int getPageNumber() {
      return pageNumber;
    }

    @Override
    public boolean isLast() {
      return isLast;
    }
  }
}
