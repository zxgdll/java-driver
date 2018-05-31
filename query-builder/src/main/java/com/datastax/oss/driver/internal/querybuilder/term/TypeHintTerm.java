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
package com.datastax.oss.driver.internal.querybuilder.term;

import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.querybuilder.term.Term;
import net.jcip.annotations.Immutable;

@Immutable
public class TypeHintTerm implements Term {

  private final Term term;
  private final DataType targetType;

  public TypeHintTerm(Term term, DataType targetType) {
    this.term = term;
    this.targetType = targetType;
  }

  @Override
  public void appendTo(StringBuilder builder) {
    builder.append('(').append(targetType.asCql(false, true)).append(')');
    term.appendTo(builder);
  }

  @Override
  public boolean isIdempotent() {
    return term.isIdempotent();
  }

  public Term getTerm() {
    return term;
  }

  public DataType getTargetType() {
    return targetType;
  }
}