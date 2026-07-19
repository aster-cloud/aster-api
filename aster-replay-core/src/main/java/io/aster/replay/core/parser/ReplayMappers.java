package io.aster.replay.core.parser;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * core-local Jackson mapper，等价 aster-api io.aster.common.JacksonMappers.DEFAULT。
 *
 * <p>★JacksonMappers 是全应用通用类（17 文件使用），不移入 replay 模块（会导致
 * 所有权倒置——core 模块不应反过来拥有 aster-api 的通用基础设施）。core 自带一份
 * 等价 mapper，配置须与 DEFAULT 逐字节等价（number/decimal/key-order 会影响
 * ReplayMetadata 哈希）——由 {@code ReplayMappersParityTest} 守门。
 *
 * <p>已核对 aster-api {@code JacksonMappers.DEFAULT} 的确切构造：vanilla
 * {@code new ObjectMapper()}，无任何 registerModule/configure/feature 调用。
 * 此处逐字等价复制。
 */
public final class ReplayMappers {

    /** 默认 ObjectMapper —— vanilla Jackson，与 JacksonMappers.DEFAULT 逐字节等价。 */
    public static final ObjectMapper DEFAULT = new ObjectMapper();

    private ReplayMappers() {}
}
