import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Checks compiled subscriber descriptors with Java 25's native class-file API; never initializes Minecraft. */
public final class NativeSubscriberAudit263 {
    public static void main(String[] args) throws Exception {
        Path classes = Path.of(args[0]);
        List<String> failures = new ArrayList<>();
        int handlers = 0;
        var parser = ClassFile.of();
        try (var paths = Files.walk(classes)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                var model = parser.parse(path);
                for (var method : model.methods()) {
                    var annotations = method.findAttribute(Attributes.runtimeVisibleAnnotations());
                    if (annotations.isEmpty() || annotations.get().annotations().stream().noneMatch(
                            a -> a.className().equalsString("Lnet/neoforged/bus/api/SubscribeEvent;"))) continue;
                    handlers++;
                    String location = model.thisClass().asInternalName().replace('/', '.') + "." + method.methodName().stringValue();
                    var type = method.methodTypeSymbol();
                    if (type.parameterCount() != 1 || !type.returnType().descriptorString().equals("V")) {
                        failures.add(location + ": subscriber must return void and take one event");
                        continue;
                    }
                    var event = type.parameterType(0);
                    if (!event.isClassOrInterface()) {
                        failures.add(location + ": subscriber parameter is not an event class");
                        continue;
                    }
                    String resource = event.descriptorString();
                    resource = resource.substring(1, resource.length() - 1);
                    try (InputStream source = NativeSubscriberAudit263.class.getClassLoader().getResourceAsStream(resource + ".class")) {
                        if (source == null) {
                            failures.add(location + ": event type missing: " + resource.replace('/', '.'));
                        } else if ((parser.parse(source.readAllBytes()).flags().flagsMask() & (ClassFile.ACC_ABSTRACT | ClassFile.ACC_INTERFACE)) != 0) {
                            failures.add(location + ": abstract event subscription: " + resource.replace('/', '.'));
                        }
                    }
                }
            }
        }
        StringBuilder json = new StringBuilder("{\n  \"target\": \"26.3\",\n  \"handlers\": ").append(handlers)
            .append(",\n  \"compiledClasses\": \"").append(escape(classes.toString())).append("\",\n  \"errors\": [");
        for (int i = 0; i < failures.size(); i++) {
            if (i != 0) json.append(',');
            json.append("\n    \"").append(escape(failures.get(i))).append('"');
        }
        json.append("\n  ]\n}\n");
        Files.writeString(Path.of(args[1]), json);
        System.out.println("Native event subscribers: " + handlers + ", errors: " + failures.size());
        failures.forEach(System.out::println);
        if (!failures.isEmpty()) System.exit(1);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
