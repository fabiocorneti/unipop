package org.unipop.util;

import org.unipop.schema.property.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Created by sbarzilay on 8/8/16.
 */
public class PropertySchemaFactory {
    public static List<PropertySchema.PropertySchemaBuilder> builders;
    private static PropertySchemaFactory self;

    private PropertySchemaFactory(List<PropertySchema.PropertySchemaBuilder> toRegister) {
        builders = new ArrayList<>();
        builders.add(new StaticPropertySchema.Builder());
        builders.add(new FieldPropertySchema.Builder());
        builders.add(new DateFieldPropertySchema.Builder());
        builders.add(new StaticDatePropertySchema.Builder());
        builders.add(new MultiPropertySchema.Builder());
        builders.add(new ConcatenateFieldPropertySchema.Builder());
        builders.add(new CoalescePropertySchema.Builder());
        toRegister.forEach(builders::add);
        Collections.reverse(builders);
    }

    public static PropertySchema createPropertySchema(String key, Object value, AbstractPropertyContainer container) {
        if (value instanceof String){
            if (value.toString().startsWith("$")) {
                Optional<PropertySchema> reference = container.getPropertySchemas().stream()
                        .filter(schema -> schema.getKey().equals(value.toString().substring(1)))
                        .findFirst();
                 if (reference.isPresent()) return reference.get();
                else throw new IllegalArgumentException("cant find reference to: " + value.toString().substring(1));
            }
        }
        Optional<PropertySchema> first = builders.stream().map(builder -> builder.build(key, value, container)).filter(schema -> schema != null).findFirst();
        if (first.isPresent()) return first.get();
        throw new IllegalArgumentException("Unrecognized property: " + key + " - " + value);
    }

    public static void addBuilder(PropertySchema.PropertySchemaBuilder builder){
        if (!builders.contains(builder)) builders.add(0, builder);
    }

    public static PropertySchemaFactory build(List<PropertySchema.PropertySchemaBuilder> toRegister){
        if (self == null) self = new PropertySchemaFactory(toRegister);
        return self;
    }
}
