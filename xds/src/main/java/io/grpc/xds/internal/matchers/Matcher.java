/*
 * Copyright 2024 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds.internal.matchers;

import javax.annotation.Nullable;

/** Unified Matcher API: xds.type.matcher.v3.Matcher. */
public abstract class Matcher<T> {
  // TODO(sergiitk): [IMPL] iterator?
  // TODO(sergiitk): [IMPL] public boolean matches(EvaluateArgs args) ?

  // TODO(sergiitk): [IMPL] AutoOneOf MatcherList, MatcherTree
  @Nullable
  public abstract MatcherList<T> matcherList();

  public abstract OnMatch<T> onNoMatch();
}
