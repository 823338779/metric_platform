package com.metricplatform.identity;

import java.util.regex.Pattern;

/** 将可信身份解析出的租户绑定到当前请求线程，退出时恢复之前的作用域。 */
public final class TenantScope implements AutoCloseable {
    private static final Pattern ID = Pattern.compile("tn_(?!00000)[0-9]{5}");
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** 重入同一租户时保留外层调用的绑定。 */
    private final String previous;

    private TenantScope(String tenantId) {
        previous = CURRENT.get();
        if (previous != null && !previous.equals(tenantId)) {
            throw new IllegalStateException("当前操作不能切换租户");
        }
        CURRENT.set(tenantId);
    }

    public static TenantScope open(String tenantId) {
        if (tenantId == null || !ID.matcher(tenantId).matches()) {
            throw new IllegalArgumentException("租户编号无效");
        }
        return new TenantScope(tenantId);
    }

    /** 仅用于判断是否处于认证前入口。 */
    public static String current() {
        return CURRENT.get();
    }

    public static String require() {
        String tenantId = current();
        if (tenantId == null) throw new IllegalStateException("缺少租户上下文");
        return tenantId;
    }

    /** 全局租户发现和开通仅能在绑定业务租户之前执行。 */
    public static void requireUnbound() {
        if (current() != null) throw new IllegalStateException("当前已绑定租户");
    }

    /** 显式接收租户编号的持久化端口必须与当前认证作用域一致。 */
    public static void require(String expectedTenant) {
        if (!require().equals(expectedTenant)) throw new IllegalStateException("持久化操作的租户与当前身份不一致");
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
