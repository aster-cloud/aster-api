rootProject.name = "aster-api"

// Composite Build: 引用独立的 aster-lang 子项目
// 这些项目已从 aster-lang 多模块项目拆分为独立项目

// Composite Build: 仅当兄弟目录存在时启用（本地开发）；CI 使用 Maven Local
listOf(
    "aster-lang-core",
    "aster-lang-runtime",
    "aster-lang-truffle",
    "aster-lang-validation",
    "aster-lang-en",
    "aster-lang-zh",
    "aster-lang-de",
).forEach { name ->
    val dir = file("../$name")
    if (dir.isDirectory) {
        includeBuild(dir) {
            dependencySubstitution {
                substitute(module("cloud.aster-lang:$name")).using(project(":"))
            }
        }
    }
}
