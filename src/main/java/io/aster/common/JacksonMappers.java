package io.aster.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * R30+ audit P2：项目里有 12+ 处 {@code new ObjectMapper()} 散落各处。
 * ObjectMapper 是线程安全但构造昂贵的对象，每个静态字段都会重复 build
 * 一套 BeanSerializer cache。把它们合并到这一个工具类里。
 *
 * <p>提供两种 mapper：
 * <ul>
 *   <li>{@link #DEFAULT}：vanilla Jackson，等价于 {@code new ObjectMapper()}。
 *       大多数 LLM / snapshot / workflow 序列化用这一个就够。</li>
 *   <li>{@link #LENIENT}：容忍未知字段 + 不写 null。给从外部网络读 JSON
 *       且 schema 可能演化的场景用（compiler、parser）。</li>
 * </ul>
 *
 * <p>新代码应当直接引用这两个 final 字段。老代码可以渐进式迁移；只要把
 * {@code private static final ObjectMapper MAPPER = new ObjectMapper()}
 * 改成 {@code import static io.aster.common.JacksonMappers.DEFAULT;}
 * 并使用 DEFAULT 即可。
 */
public final class JacksonMappers {

    private JacksonMappers() {}

    /** 默认 ObjectMapper —— vanilla Jackson，无任何 feature toggle。 */
    public static final ObjectMapper DEFAULT = new ObjectMapper();

    /** 宽松 mapper —— 不抛 UnknownProperty，给从网络/磁盘读的 JSON 用。 */
    public static final ObjectMapper LENIENT = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
}
