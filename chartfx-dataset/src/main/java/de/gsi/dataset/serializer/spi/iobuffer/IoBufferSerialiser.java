package de.gsi.dataset.serializer.spi.iobuffer;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.WeakHashMap;

import de.gsi.dataset.serializer.IoSerialiser;
import de.gsi.dataset.serializer.spi.AbstractSerialiser;
import de.gsi.dataset.serializer.spi.ClassDescriptions;
import de.gsi.dataset.serializer.spi.ClassFieldDescription;
import de.gsi.dataset.serializer.spi.FieldHeader;
import de.gsi.dataset.serializer.spi.FieldSerialiser;
import de.gsi.dataset.serializer.spi.FieldSerialiser.FieldSerialiserFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * reference implementation for streaming arbitrary object to and from a IoBuffer-based byte-buffer
 *
 * @author rstein
 */
public class IoBufferSerialiser extends AbstractSerialiser {
    private static final Logger LOGGER = LoggerFactory.getLogger(IoBufferSerialiser.class);
    private final Map<Integer, WeakHashMap<String, ClassFieldDescription>> fieldToClassFieldDescription = new HashMap<>();
    private final IoSerialiser ioSerialiser;

    /**
     * Initialises new IoBuffer-backed object serialiser
     *
     * @param ioSerialiser the backing IoSerialiser (see e.g. {@link de.gsi.dataset.serializer.IoSerialiser}
     * TODO: add links to reference implementations
     */
    public IoBufferSerialiser(final IoSerialiser ioSerialiser) {
        super();
        if (ioSerialiser == null) {
            throw new IllegalArgumentException("buffer must not be null");
        }
        this.ioSerialiser = ioSerialiser;
        startMarkerFunction = ioSerialiser::putStartMarker;
        endMarkerFunction = ioSerialiser::putEndMarker;

        // register primitive and boxed data type handlers
        FieldPrimitiveValueHelper.register(this, ioSerialiser);
        FieldPrimitveValueArrayHelper.register(this, ioSerialiser);
        FieldBoxedValueHelper.register(this, ioSerialiser);
        FieldBoxedValueArrayHelper.register(this, ioSerialiser);

        // Collection serialiser mapper to IoBuffer
        final FieldSerialiserFunction collectionReader = (obj, field) -> {
            final Collection<?> origCollection = (Collection<?>) field.getField().get(obj);
            origCollection.clear();

            final Collection<?> setVal = ioSerialiser.getCollection(origCollection);
            field.getField().set(obj, setVal);
        }; // reader
        final FieldSerialiserFunction collectionWriter = (obj, field) -> {
            final Collection<?> retVal = (Collection<?>) field.getField().get(obj);
            ioSerialiser.put(field.getFieldName(), retVal); // writer
        };
        addClassDefinition(new IoBufferFieldSerialiser(ioSerialiser, collectionReader, collectionWriter, Collection.class));
        addClassDefinition(new IoBufferFieldSerialiser(ioSerialiser, collectionReader, collectionWriter, List.class));
        addClassDefinition(new IoBufferFieldSerialiser(ioSerialiser, collectionReader, collectionWriter, Queue.class));
        addClassDefinition(new IoBufferFieldSerialiser(ioSerialiser, collectionReader, collectionWriter, Set.class));

        // Enum serialiser mapper to IoBuffer
        addClassDefinition(new IoBufferFieldSerialiser(ioSerialiser, //
                (obj, field) -> field.getField().set(obj, ioSerialiser.getEnum((Enum<?>) field.getField().get(obj))), // reader
                (obj, field) -> ioSerialiser.put(field.getFieldName(), (Enum<?>) field.getField().get(obj)), // writer
                Enum.class));

        // Map serialiser mapper to IoBuffer
        addClassDefinition(new IoBufferFieldSerialiser(ioSerialiser, //
                (obj, field) -> { // reader
                    final Map<?, ?> origMap = (Map<?, ?>) field.getField().get(obj);
                    origMap.clear();
                    final Map<?, ?> setVal = ioSerialiser.getMap(origMap);

                    field.getField().set(obj, setVal);
                }, // writer
                (obj, field) -> {
                    final Map<?, ?> retVal = (Map<?, ?>) field.getField().get(obj);
                    ioSerialiser.put(field.getFieldName(), retVal);
                },
                Map.class));

        FieldDataSetHelper.register(this, ioSerialiser);

        // addClassDefinition(null, StringHashMapList.class);
    }

    protected void deserialise(final Object obj, final FieldHeader fieldRoot, final ClassFieldDescription classFieldDescription, final int recursionDepth) throws IllegalAccessException {
        final String ioName = fieldRoot.getFieldName();

        if (!ioName.equals(classFieldDescription.getFieldName())) {
            // did not find matching (sub-)field in class
            if (fieldRoot.getChildren().isEmpty()) {
                return;
            }
            // check for potential inner fields
            for (final FieldHeader fieldHeader : fieldRoot.getChildren()) {
                final String fieldName = fieldHeader.getFieldName();
                Map<String, ClassFieldDescription> rMap = fieldToClassFieldDescription.computeIfAbsent(recursionDepth, depth -> new WeakHashMap<>());
                final ClassFieldDescription subFieldDescription = rMap.computeIfAbsent(fieldName, name -> classFieldDescription.getChildren().stream().filter(e -> e.getFieldName().equals(fieldName)).findFirst().get());

                if (subFieldDescription != null) {
                    deserialise(obj, fieldHeader, subFieldDescription, recursionDepth + 1);
                }
            }
            return;
        }

        final Class<?> fieldClass = classFieldDescription.getType();
        if (classFieldDescription.isFinal() && !fieldClass.isInterface()) {
            // cannot set final variables
            LOGGER.atWarn().addArgument(classFieldDescription.getFieldNameRelative()).log("cannot set final field '{}'");
            return;
        }

        final Optional<FieldSerialiser> serialiser = findFieldSerialiserForKnownClassOrInterface(fieldClass, classFieldDescription.getActualTypeArguments());

        if (serialiser.isEmpty()) {
            final Object ref = classFieldDescription.getField().get(obj);
            final Object subRef;
            if (ref == null) {
                subRef = classFieldDescription.allocateMemberClassField(obj);
            } else {
                subRef = ref;
            }

            // no specific deserialiser present check for potential inner fields
            for (final FieldHeader fieldHeader : fieldRoot.getChildren()) {
                final String fieldName = fieldHeader.getFieldName();
                Map<String, ClassFieldDescription> rMap = fieldToClassFieldDescription.computeIfAbsent(recursionDepth, depth -> new WeakHashMap<>());
                final ClassFieldDescription subFieldDescription = rMap.computeIfAbsent(fieldName, name -> classFieldDescription.getChildren().stream().filter(e -> e.getFieldName().equals(fieldName)).findFirst().get());

                if (subFieldDescription != null) {
                    deserialise(subRef, fieldHeader, subFieldDescription, recursionDepth + 1);
                }
            }
            return;
        }

        ioSerialiser.getBuffer().position(fieldRoot.getDataBufferPosition());
        serialiser.get().getReaderFunction().exec(obj, classFieldDescription);
    }

    @Override
    public Object deserialiseObject(final Object obj) throws IllegalAccessException {
        if (obj == null) {
            throw new IllegalArgumentException("obj must not be null (yet)");
        }

        final long startPosition = ioSerialiser.getBuffer().position();
        // final HeaderInfo bufferHeader = ioSerialiser.checkHeaderInfo();

        // match field header with class field description
        final ClassFieldDescription classFieldDescription = ClassDescriptions.get(obj.getClass());

        ioSerialiser.getBuffer().position(startPosition);
        final FieldHeader fieldRoot = ioSerialiser.parseIoStream();
        // deserialise into object
        for (final FieldHeader child : fieldRoot.getChildren()) {
            deserialise(obj, child, classFieldDescription, 0);
        }

        return obj;
    }

    @Override
    public void serialiseObject(final Object obj) throws IllegalAccessException {
        ioSerialiser.putHeaderInfo();

        super.serialiseObject(obj);

        ioSerialiser.putEndMarker("OBJ_ROOT_END");
    }
}
