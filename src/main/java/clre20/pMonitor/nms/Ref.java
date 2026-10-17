package clre20.pMonitor.nms;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 反射工具：Paper 1.20.5 起執行時使用 Mojang 名稱，所以可以用名稱直接找類別與成員。
 * 所有找不到的成員都會丟出 {@link NmsException}，由啟動自我檢查統一回報。
 */
public final class Ref {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /*
     * Paper 1.21.x 會改寫所有插件的反射呼叫（Class.forName、getDeclaredField…），
     * 把名稱當成 Spigot 名稱轉成 Mojang 名稱；但本插件用的已經是 Mojang 名稱，
     * 部分名稱會撞名（例如 Mojang 的 WorldData 被誤轉成 LevelData）。
     * 透過 Method.invoke 呼叫這些反射方法即可避開改寫。
     */
    private static final Method RAW_FOR_NAME;
    private static final Method RAW_DECLARED_FIELD;
    private static final Method RAW_DECLARED_METHOD;

    static {
        try {
            RAW_FOR_NAME = Class.class.getMethod("forName", String.class, boolean.class, ClassLoader.class);
            RAW_DECLARED_FIELD = Class.class.getMethod("getDeclaredField", String.class);
            RAW_DECLARED_METHOD = Class.class.getMethod("getDeclaredMethod", String.class, Class[].class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Ref() {
    }

    private static Class<?> rawForName(String name) throws ClassNotFoundException {
        try {
            return (Class<?>) RAW_FOR_NAME.invoke(null, name, false, Ref.class.getClassLoader());
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof ClassNotFoundException cnf) {
                throw cnf;
            }
            if (e.getCause() instanceof LinkageError le) {
                throw le;
            }
            throw new ClassNotFoundException(name, e.getCause());
        } catch (IllegalAccessException e) {
            throw new ClassNotFoundException(name, e);
        }
    }

    private static Field rawDeclaredField(Class<?> c, String name) throws NoSuchFieldException {
        try {
            return (Field) RAW_DECLARED_FIELD.invoke(c, name);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof NoSuchFieldException nsf) {
                throw nsf;
            }
            throw new NoSuchFieldException(name);
        } catch (IllegalAccessException e) {
            throw new NoSuchFieldException(name);
        }
    }

    private static Method rawDeclaredMethod(Class<?> c, String name, Class<?>... params) throws NoSuchMethodException {
        try {
            return (Method) RAW_DECLARED_METHOD.invoke(c, name, params);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof NoSuchMethodException nsm) {
                throw nsm;
            }
            throw new NoSuchMethodException(name);
        } catch (IllegalAccessException e) {
            throw new NoSuchMethodException(name);
        }
    }

    // ---------------------------------------------------------------- 類別

    public static Class<?> cls(String name) {
        try {
            return rawForName(name);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new NmsException("找不到類別 " + name);
        }
    }

    public static Class<?> clsOrNull(String name) {
        try {
            return rawForName(name);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** 依序嘗試多個名稱，回傳第一個存在的類別。 */
    public static Class<?> clsAny(String... names) {
        for (String name : names) {
            Class<?> c = clsOrNull(name);
            if (c != null) {
                return c;
            }
        }
        throw new NmsException("找不到類別 " + String.join(" / ", names));
    }

    // ---------------------------------------------------------------- 欄位

    public static Field field(Class<?> owner, String name) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Field f = rawDeclaredField(c, name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            } catch (RuntimeException e) {
                throw new NmsException("無法存取欄位 " + owner.getName() + "." + name + "：" + e.getMessage());
            }
        }
        throw new NmsException("找不到欄位 " + owner.getName() + "." + name);
    }

    public static Field fieldOrNull(Class<?> owner, String name) {
        try {
            return field(owner, name);
        } catch (NmsException e) {
            return null;
        }
    }

    /** 找第一個型別相符的欄位（名稱不穩定時使用）。 */
    public static Field fieldByType(Class<?> owner, Class<?> type) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        throw new NmsException("找不到型別為 " + type.getName() + " 的欄位於 " + owner.getName());
    }

    public static Getter getter(Class<?> owner, String name) {
        Field f = field(owner, name);
        try {
            MethodHandle mh = LOOKUP.unreflectGetter(f);
            if (Modifier.isStatic(f.getModifiers())) {
                mh = MethodHandles.dropArguments(mh, 0, Object.class);
            }
            return new Getter(mh.asType(MethodType.methodType(Object.class, Object.class)), owner.getSimpleName() + "." + name);
        } catch (IllegalAccessException e) {
            throw new NmsException("無法讀取欄位 " + owner.getName() + "." + name);
        }
    }

    public static Object staticValue(Class<?> owner, String name) {
        try {
            return field(owner, name).get(null);
        } catch (IllegalAccessException e) {
            throw new NmsException("無法讀取靜態欄位 " + owner.getName() + "." + name);
        }
    }

    public static Object read(Object target, String name) {
        try {
            return field(target.getClass(), name).get(target);
        } catch (IllegalAccessException e) {
            throw new NmsException("無法讀取欄位 " + target.getClass().getName() + "." + name);
        }
    }

    public static void write(Object target, String name, Object value) {
        try {
            field(target.getClass(), name).set(target, value);
        } catch (IllegalAccessException e) {
            throw new NmsException("無法寫入欄位 " + target.getClass().getName() + "." + name);
        }
    }

    // ---------------------------------------------------------------- 方法

    public static Method method(Class<?> owner, String name, Class<?>... params) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Method m = rawDeclaredMethod(c, name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            } catch (RuntimeException e) {
                throw new NmsException("無法存取方法 " + owner.getName() + "." + name + "：" + e.getMessage());
            }
        }
        // 介面的 default 方法
        for (Class<?> i : allInterfaces(owner)) {
            try {
                Method m = rawDeclaredMethod(i, name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NmsException("找不到方法 " + owner.getName() + "." + name + "(" + params.length + " 個參數)");
    }

    /** 依名稱與參數數量找方法（參數型別在不同版本會改名時使用）。 */
    public static Method methodByArity(Class<?> owner, String name, int arity) {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == arity && !m.isSynthetic() && !m.isBridge()) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        for (Class<?> i : allInterfaces(owner)) {
            for (Method m : i.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == arity) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new NmsException("找不到方法 " + owner.getName() + "." + name + "(" + arity + " 個參數)");
    }

    public static Method methodOrNull(Class<?> owner, String name, Class<?>... params) {
        try {
            return method(owner, name, params);
        } catch (NmsException e) {
            return null;
        }
    }

    public static Invoker invoker(Method m) {
        try {
            MethodHandle mh = LOOKUP.unreflect(m);
            int count = m.getParameterCount() + (Modifier.isStatic(m.getModifiers()) ? 0 : 1);
            if (Modifier.isStatic(m.getModifiers())) {
                mh = MethodHandles.dropArguments(mh, 0, Object.class);
                count++;
            }
            mh = mh.asType(MethodType.genericMethodType(count));
            return new Invoker(mh.asSpreader(Object[].class, count - 1), m.getDeclaringClass().getSimpleName() + "." + m.getName());
        } catch (IllegalAccessException e) {
            throw new NmsException("無法呼叫方法 " + m);
        }
    }

    public static Invoker invoker(Class<?> owner, String name, Class<?>... params) {
        return invoker(method(owner, name, params));
    }

    /**
     * 每 tick 都會呼叫的方法用：轉成固定型別的 MethodHandle，以 invokeExact 呼叫，
     * 不需要建立參數陣列，回傳值也不會裝箱。
     */
    public static Fast fast(Class<?> owner, String name, Class<?> ret, Class<?>... params) {
        return fast(method(owner, name, params), ret, params.length);
    }

    /** 同 {@link #fast(Class, String, Class, Class[])}，所有參數視為 Object。 */
    public static Fast fast(Method m, Class<?> ret, int paramCount) {
        Class<?>[] params = new Class<?>[paramCount];
        for (int i = 0; i < paramCount; i++) {
            Class<?> t = m.getParameterTypes()[i];
            params[i] = t.isPrimitive() ? t : Object.class;
        }
        String owner = m.getDeclaringClass().getSimpleName();
        String name = m.getName();
        try {
            MethodHandle mh = LOOKUP.unreflect(m);
            Class<?>[] p = new Class<?>[params.length + 1];
            p[0] = Object.class;
            System.arraycopy(params, 0, p, 1, params.length);
            return new Fast(mh.asType(MethodType.methodType(ret, p)), owner + "." + name);
        } catch (IllegalAccessException e) {
            throw new NmsException("無法呼叫方法 " + m);
        }
    }

    public static Object call(Object target, String name) {
        try {
            return method(target.getClass(), name).invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new NmsException("呼叫 " + target.getClass().getName() + "." + name + " 失敗：" + rootMessage(e));
        }
    }

    public static Object callStatic(Class<?> owner, String name, Class<?>[] types, Object... args) {
        try {
            return method(owner, name, types).invoke(null, args);
        } catch (ReflectiveOperationException e) {
            throw new NmsException("呼叫 " + owner.getName() + "." + name + " 失敗：" + rootMessage(e));
        }
    }

    // ---------------------------------------------------------------- 建構子

    public static Constructor<?> ctor(Class<?> owner, Class<?>... params) {
        try {
            Constructor<?> c = owner.getDeclaredConstructor(params);
            c.setAccessible(true);
            return c;
        } catch (NoSuchMethodException e) {
            throw new NmsException("找不到建構子 " + owner.getName() + "(" + params.length + " 個參數)");
        }
    }

    public static Constructor<?> ctorOrNull(Class<?> owner, Class<?>... params) {
        try {
            return ctor(owner, params);
        } catch (NmsException e) {
            return null;
        }
    }

    public static Object construct(Constructor<?> c, Object... args) {
        try {
            return c.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new NmsException("建立 " + c.getDeclaringClass().getSimpleName() + " 失敗：" + rootMessage(e));
        }
    }

    // ---------------------------------------------------------------- record

    public static Object component(Object record, String name) {
        RecordComponent[] comps = record.getClass().getRecordComponents();
        if (comps == null) {
            throw new NmsException(record.getClass().getName() + " 不是 record");
        }
        for (RecordComponent rc : comps) {
            if (rc.getName().equals(name)) {
                try {
                    Method acc = rc.getAccessor();
                    acc.setAccessible(true);
                    return acc.invoke(record);
                } catch (ReflectiveOperationException e) {
                    throw new NmsException("讀取 " + record.getClass().getSimpleName() + "." + name + " 失敗");
                }
            }
        }
        throw new NmsException(record.getClass().getName() + " 沒有欄位 " + name);
    }

    public static boolean hasComponent(Class<?> recordClass, String name) {
        RecordComponent[] comps = recordClass.getRecordComponents();
        if (comps == null) {
            return false;
        }
        for (RecordComponent rc : comps) {
            if (rc.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 用欄位名稱建立 record；沒指定的欄位用型別預設值（false、0、空集合、Optional.empty、null）。 */
    public static Object buildRecord(Class<?> recordClass, Map<String, Object> values) {
        RecordComponent[] comps = recordClass.getRecordComponents();
        if (comps == null) {
            throw new NmsException(recordClass.getName() + " 不是 record");
        }
        Class<?>[] types = new Class<?>[comps.length];
        Object[] args = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            args[i] = values.containsKey(comps[i].getName()) ? values.get(comps[i].getName()) : defaultValue(types[i]);
        }
        return construct(ctor(recordClass, types), args);
    }

    /** 複製 record 並覆寫部分欄位。 */
    public static Object copyRecord(Object record, Map<String, Object> overrides) {
        Map<String, Object> values = new HashMap<>();
        for (RecordComponent rc : record.getClass().getRecordComponents()) {
            values.put(rc.getName(), overrides.containsKey(rc.getName()) ? overrides.get(rc.getName()) : component(record, rc.getName()));
        }
        return buildRecord(record.getClass(), values);
    }

    public static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        if (type == Optional.class) return Optional.empty();
        if (type == List.class || type == Collection.class) return List.of();
        if (type == Set.class) return Set.of();
        if (type == Map.class) return Map.of();
        return null;
    }

    // ---------------------------------------------------------------- 其他

    public static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + (cur.getMessage() == null ? "" : ": " + cur.getMessage());
    }

    private static List<Class<?>> allInterfaces(Class<?> owner) {
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                collect(i, out);
            }
        }
        return out;
    }

    private static void collect(Class<?> i, List<Class<?>> out) {
        if (out.contains(i)) {
            return;
        }
        out.add(i);
        for (Class<?> p : i.getInterfaces()) {
            collect(p, out);
        }
    }

    /** 讀取欄位用的快速存取器。 */
    public record Getter(MethodHandle handle, String name) {
        public Object get(Object target) {
            try {
                return this.handle.invokeExact(target);
            } catch (Throwable t) {
                throw new NmsException("讀取 " + this.name + " 失敗：" + rootMessage(t));
            }
        }
    }

    /** 固定型別的快速呼叫（見 {@link #fast}）。 */
    public record Fast(MethodHandle handle, String name) {
        public double getDouble(Object target) {
            try {
                return (double) this.handle.invokeExact(target);
            } catch (Throwable t) {
                throw new NmsException("呼叫 " + this.name + " 失敗：" + rootMessage(t), t);
            }
        }

        public float getFloat(Object target) {
            try {
                return (float) this.handle.invokeExact(target);
            } catch (Throwable t) {
                throw new NmsException("呼叫 " + this.name + " 失敗：" + rootMessage(t), t);
            }
        }

        public boolean getBoolean(Object target) {
            try {
                return (boolean) this.handle.invokeExact(target);
            } catch (Throwable t) {
                throw new NmsException("呼叫 " + this.name + " 失敗：" + rootMessage(t), t);
            }
        }

        public int getInt(Object target) {
            try {
                return (int) this.handle.invokeExact(target);
            } catch (Throwable t) {
                throw new NmsException("呼叫 " + this.name + " 失敗：" + rootMessage(t), t);
            }
        }

        public void run2(Object target, Object a, Object b) {
            try {
                this.handle.invokeExact(target, a, b);
            } catch (Throwable t) {
                throw new NmsException("呼叫 " + this.name + " 失敗：" + rootMessage(t), t);
            }
        }

        public Object callInt(Object target, int arg) {
            try {
                return (Object) this.handle.invokeExact(target, arg);
            } catch (Throwable t) {
                throw new NmsException("呼叫 " + this.name + " 失敗：" + rootMessage(t), t);
            }
        }
    }

    /** 呼叫方法用的快速存取器；靜態方法的 target 傳 null。 */
    public record Invoker(MethodHandle handle, String name) {
        public Object call(Object target, Object... args) {
            try {
                return this.handle.invoke(target, args);
            } catch (Throwable t) {
                throw new NmsException("呼叫 " + this.name + " 失敗：" + rootMessage(t), t);
            }
        }
    }
}
