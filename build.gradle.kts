plugins {
    alias(libs.plugins.android.application) apply false
    // kotlin-compose 留到 S5（UI 阶段）—— 现在装上会因为 classpath 里没有
    // compose runtime 而报 noRuntimeOnClasspathError
}
