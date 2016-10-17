// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.rules;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.ConfigurationFragmentFactory;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.bazel.rules.BazelRuleClassProvider.RuleModule;
import com.google.devtools.build.lib.packages.RuleClass;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests consistency of {@link BazelRuleClassProvider}.
 */
@RunWith(JUnit4.class)
public class BazelRuleClassProviderTest {
  private void checkConfigConsistency(ConfiguredRuleClassProvider provider) {
    // Check that every fragment required by a rule is present.
    Set<Class<? extends BuildConfiguration.Fragment>> configurationFragments =
        provider.getAllFragments();
    for (RuleClass ruleClass : provider.getRuleClassMap().values()) {
      for (Class<?> fragment :
          ruleClass.getConfigurationFragmentPolicy().getRequiredConfigurationFragments()) {
        assertWithMessage(ruleClass.toString()).that(configurationFragments).contains(fragment);
      }
    }

    List<Class<? extends FragmentOptions>> configOptions = provider.getConfigurationOptions();
    for (ConfigurationFragmentFactory fragmentFactory : provider.getConfigurationFragments()) {
      // Check that every created fragment is present.
      assertThat(configurationFragments).contains(fragmentFactory.creates());
      // Check that every options class required for fragment creation is provided.
      for (Class<? extends FragmentOptions> options : fragmentFactory.requiredOptions()) {
        assertThat(configOptions).contains(options);
      }
    }
  }

  private void checkModule(RuleModule top) {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    builder.setToolsRepository(BazelRuleClassProvider.TOOLS_REPOSITORY);
    Set<RuleModule> result = new HashSet<>();
    collectTransitiveClosure(result, top);
    for (RuleModule module : result) {
      module.init(builder);
    }
    ConfiguredRuleClassProvider provider = builder.build();
    assertThat(provider).isNotNull();
    checkConfigConsistency(provider);
  }

  private void collectTransitiveClosure(Set<RuleModule> result, RuleModule module) {
    if (result.add(module)) {
      for (RuleModule dep : module.requires()) {
        collectTransitiveClosure(result, dep);
      }
    }
  }

  @Test
  public void minimalConsistency() {
    checkModule(BazelRuleClassProvider.MINIMAL_RULES);
  }

  @Test
  public void shConsistency() {
    checkModule(BazelRuleClassProvider.SH_RULES);
  }

  @Test
  public void protoConsistency() {
    checkModule(BazelRuleClassProvider.PROTO_RULES);
  }

  @Test
  public void cppConsistency() {
    checkModule(BazelRuleClassProvider.CPP_RULES);
  }

  @Test
  public void javaConsistency() {
    checkModule(BazelRuleClassProvider.JAVA_RULES);
  }

  @Test
  public void pythonConsistency() {
    checkModule(BazelRuleClassProvider.PYTHON_RULES);
  }

  @Test
  public void androidConsistency() {
    checkModule(BazelRuleClassProvider.ANDROID_RULES);
  }

  @Test
  public void objcConsistency() {
    checkModule(BazelRuleClassProvider.OBJC_RULES);
  }

  @Test
  public void androidStudioConsistency() {
    checkModule(BazelRuleClassProvider.ANDROID_STUDIO_ASPECT);
  }

  @Test
  public void variousWorkspaceConsistency() {
    checkModule(BazelRuleClassProvider.VARIOUS_WORKSPACE_RULES);
  }
}