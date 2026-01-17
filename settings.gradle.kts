rootProject.name = "aster-api"

// Composite Build: 引用独立的 aster-lang 子项目
// 这些项目已从 aster-lang 多模块项目拆分为独立项目

// 核心语言模块 - 独立项目
includeBuild("../aster-lang-core") {
    dependencySubstitution {
        substitute(module("cloud.aster-lang:aster-lang-core")).using(project(":"))
    }
}

// 运行时模块 - 独立项目
includeBuild("../aster-lang-runtime") {
    dependencySubstitution {
        substitute(module("cloud.aster-lang:aster-lang-runtime")).using(project(":"))
    }
}

// Truffle 集成模块 - 独立项目
includeBuild("../aster-lang-truffle") {
    dependencySubstitution {
        substitute(module("cloud.aster-lang:aster-lang-truffle")).using(project(":"))
    }
}

// 验证模块 - 独立项目
includeBuild("../aster-lang-validation") {
    dependencySubstitution {
        substitute(module("cloud.aster-lang:aster-lang-validation")).using(project(":"))
    }
}
