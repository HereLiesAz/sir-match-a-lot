// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.kotlin.multiplatform.library) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  // Only :desktopApp applies this (below) — hot reload runs a Compose JVM
  // app under the JetBrains Runtime's enhanced class redefinition, which
  // doesn't apply to :app's Android target. Declared here with apply
  // false anyway, matching every other plugin in this file, so its
  // version is resolved once instead of independently per module.
  alias(libs.plugins.compose.hot.reload) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
}