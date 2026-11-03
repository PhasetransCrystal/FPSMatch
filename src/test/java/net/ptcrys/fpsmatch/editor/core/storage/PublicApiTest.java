package net.ptcrys.fpsmatch.editor.core.storage;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Every compiled public type participates, so a newly exposed API cannot silently skip this check. */
class PublicApiTest {
    @Test void publicApiContainsOnlyStorageAndJdkTypes() throws Exception {
        List<Class<?>> exported = new ArrayList<>();
        for (String directory : System.getProperty("storage.main.classes").split(File.pathSeparator)) {
            Path root = Path.of(directory);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    String relative = root.relativize(file).toString();
                    String name = relative.substring(0, relative.length() - 6).replace(File.separatorChar, '.');
                    Class<?> type = Class.forName(name, false, getClass().getClassLoader());
                    if (Modifier.isPublic(type.getModifiers())) {
                        exported.add(type);
                    }
                }
            }
        }
        assertTrue(exported.contains(LocalProjectStore.class), "compiled main classes must be inspected");
        for (Class<?> type : exported) {
            for (Type parameter : type.getTypeParameters()) {
                check(parameter);
            }
            check(type.getGenericSuperclass());
            for (Type parent : type.getGenericInterfaces()) {
                check(parent);
            }
            for (var method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) || Modifier.isProtected(method.getModifiers())) {
                    for (Type parameter : method.getTypeParameters()) {
                        check(parameter);
                    }
                    check(method.getGenericReturnType());
                    for (Type parameter : method.getGenericParameterTypes()) {
                        check(parameter);
                    }
                    for (Type exception : method.getGenericExceptionTypes()) {
                        check(exception);
                    }
                }
            }
            for (var constructor : type.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers()) || Modifier.isProtected(constructor.getModifiers())) {
                    for (Type parameter : constructor.getTypeParameters()) {
                        check(parameter);
                    }
                    for (Type parameter : constructor.getGenericParameterTypes()) {
                        check(parameter);
                    }
                    for (Type exception : constructor.getGenericExceptionTypes()) {
                        check(exception);
                    }
                }
            }
            for (var field : type.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers()) || Modifier.isProtected(field.getModifiers())) {
                    check(field.getGenericType());
                }
            }
        }
    }

    private static void check(Type type) {
        check(type, new HashSet<>());
    }

    private static void check(Type type, Set<Type> visited) {
        if (type == null || !visited.add(type)) {
            return;
        }
        if (type instanceof Class<?> klass) {
            if (klass.isArray()) {
                check(klass.getComponentType(), visited);
            } else {
                assertTrue(klass.isPrimitive() || klass.getName().startsWith("java.")
                        && !klass.getName().startsWith("java.awt.")
                        || klass.getName().startsWith("net.ptcrys.fpsmatch.editor.core.storage."),
                        () -> "platform API leaked a host or implementation type: " + klass.getName());
            }
        } else if (type instanceof ParameterizedType parameterized) {
            check(parameterized.getRawType(), visited);
            check(parameterized.getOwnerType(), visited);
            for (Type argument : parameterized.getActualTypeArguments()) {
                check(argument, visited);
            }
        } else if (type instanceof GenericArrayType array) {
            check(array.getGenericComponentType(), visited);
        } else if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) {
                check(bound, visited);
            }
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                check(bound, visited);
            }
            for (Type bound : wildcard.getLowerBounds()) {
                check(bound, visited);
            }
        } else {
            fail("unsupported public signature: " + type);
        }
    }
}
