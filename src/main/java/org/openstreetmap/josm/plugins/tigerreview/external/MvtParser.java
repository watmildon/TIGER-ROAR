// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.tigerreview.external;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal parser for Mapbox Vector Tiles (MVT) protobuf format.
 *
 * <p>Only supports the subset needed for reading point features with
 * string/integer properties from Mapillary traffic sign tiles.
 * Implements raw protobuf wire format decoding without external libraries.</p>
 *
 * @see <a href="https://github.com/mapbox/vector-tile-spec/blob/master/2.1/vector_tile.proto">MVT 2.1 spec</a>
 */
final class MvtParser {

    private MvtParser() {
        // utility class
    }

    /** Protobuf wire types */
    private static final int WIRETYPE_VARINT = 0;
    private static final int WIRETYPE_64BIT = 1;
    private static final int WIRETYPE_LENGTH_DELIMITED = 2;
    private static final int WIRETYPE_32BIT = 5;

    /**
     * A parsed MVT layer.
     */
    static class Layer {
        String name = "";
        int extent = 4096;
        final List<String> keys = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        final List<Feature> features = new ArrayList<>();
    }

    /**
     * A parsed MVT feature.
     */
    static class Feature {
        long id;
        int geomType;
        final List<Integer> tags = new ArrayList<>();
        final List<Integer> geometry = new ArrayList<>();

        /**
         * Get a property value by key name, using the layer's key/value tables.
         */
        Object getProperty(String key, Layer layer) {
            for (int i = 0; i + 1 < tags.size(); i += 2) {
                int keyIdx = tags.get(i);
                int valIdx = tags.get(i + 1);
                if (keyIdx < layer.keys.size() && valIdx < layer.values.size()
                        && key.equals(layer.keys.get(keyIdx))) {
                    return layer.values.get(valIdx);
                }
            }
            return null;
        }

        /**
         * Decode point geometry to tile-relative coordinates [x, y].
         * Returns null if geometry is not a point or is empty.
         */
        int[] decodePoint() {
            if (geometry.size() < 3) return null;
            int cmdInt = geometry.get(0);
            int cmd = cmdInt & 0x7;
            if (cmd != 1) return null; // MoveTo command
            // Decode zigzag-encoded deltas
            int x = zigzagDecode(geometry.get(1));
            int y = zigzagDecode(geometry.get(2));
            return new int[]{x, y};
        }

        private static int zigzagDecode(int n) {
            return (n >>> 1) ^ -(n & 1);
        }
    }

    /**
     * Parse an MVT tile from raw protobuf bytes.
     *
     * @param data the raw MVT protobuf bytes
     * @return list of layers in the tile
     * @throws IOException if the data is malformed
     */
    static List<Layer> parse(byte[] data) throws IOException {
        List<Layer> layers = new ArrayList<>();
        ProtobufReader reader = new ProtobufReader(data);

        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;

            if (fieldNumber == 3 && wireType == WIRETYPE_LENGTH_DELIMITED) {
                // Tile.layers
                byte[] layerData = reader.readBytes();
                layers.add(parseLayer(layerData));
            } else {
                reader.skip(wireType);
            }
        }

        return layers;
    }

    private static Layer parseLayer(byte[] data) throws IOException {
        Layer layer = new Layer();
        ProtobufReader reader = new ProtobufReader(data);

        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;

            switch (fieldNumber) {
                case 1: // name
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        layer.name = reader.readString();
                    } else {
                        reader.skip(wireType);
                    }
                    break;
                case 2: // features
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        byte[] featureData = reader.readBytes();
                        layer.features.add(parseFeature(featureData));
                    } else {
                        reader.skip(wireType);
                    }
                    break;
                case 3: // keys
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        layer.keys.add(reader.readString());
                    } else {
                        reader.skip(wireType);
                    }
                    break;
                case 4: // values
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        byte[] valueData = reader.readBytes();
                        layer.values.add(parseValue(valueData));
                    } else {
                        reader.skip(wireType);
                    }
                    break;
                case 5: // extent
                    if (wireType == WIRETYPE_VARINT) {
                        layer.extent = (int) reader.readVarint();
                    } else {
                        reader.skip(wireType);
                    }
                    break;
                default:
                    reader.skip(wireType);
                    break;
            }
        }

        return layer;
    }

    private static Feature parseFeature(byte[] data) throws IOException {
        Feature feature = new Feature();
        ProtobufReader reader = new ProtobufReader(data);

        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;

            switch (fieldNumber) {
                case 1: // id
                    if (wireType == WIRETYPE_VARINT) {
                        feature.id = reader.readVarint();
                    } else {
                        reader.skip(wireType);
                    }
                    break;
                case 2: // tags (packed repeated uint32)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        byte[] packed = reader.readBytes();
                        ProtobufReader packedReader = new ProtobufReader(packed);
                        while (packedReader.hasRemaining()) {
                            feature.tags.add((int) packedReader.readVarint());
                        }
                    } else if (wireType == WIRETYPE_VARINT) {
                        feature.tags.add((int) reader.readVarint());
                    } else {
                        reader.skip(wireType);
                    }
                    break;
                case 3: // type (GeomType enum as varint)
                    if (wireType == WIRETYPE_VARINT) {
                        feature.geomType = (int) reader.readVarint();
                    } else {
                        reader.skip(wireType);
                    }
                    break;
                case 4: // geometry (packed repeated uint32)
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        byte[] packed = reader.readBytes();
                        ProtobufReader packedReader = new ProtobufReader(packed);
                        while (packedReader.hasRemaining()) {
                            feature.geometry.add((int) packedReader.readVarint());
                        }
                    } else if (wireType == WIRETYPE_VARINT) {
                        feature.geometry.add((int) reader.readVarint());
                    } else {
                        reader.skip(wireType);
                    }
                    break;
                default:
                    reader.skip(wireType);
                    break;
            }
        }

        return feature;
    }

    private static Object parseValue(byte[] data) throws IOException {
        ProtobufReader reader = new ProtobufReader(data);

        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x7;

            switch (fieldNumber) {
                case 1: // string_value
                    if (wireType == WIRETYPE_LENGTH_DELIMITED) {
                        return reader.readString();
                    }
                    reader.skip(wireType);
                    break;
                case 2: // float_value
                    if (wireType == WIRETYPE_32BIT) {
                        return reader.readFloat();
                    }
                    reader.skip(wireType);
                    break;
                case 3: // double_value
                    if (wireType == WIRETYPE_64BIT) {
                        return reader.readDouble();
                    }
                    reader.skip(wireType);
                    break;
                case 4: // int_value
                    if (wireType == WIRETYPE_VARINT) {
                        return reader.readVarint();
                    }
                    reader.skip(wireType);
                    break;
                case 5: // uint_value
                    if (wireType == WIRETYPE_VARINT) {
                        return reader.readVarint();
                    }
                    reader.skip(wireType);
                    break;
                case 6: // sint_value
                    if (wireType == WIRETYPE_VARINT) {
                        long raw = reader.readVarint();
                        return (raw >>> 1) ^ -(raw & 1); // zigzag
                    }
                    reader.skip(wireType);
                    break;
                case 7: // bool_value
                    if (wireType == WIRETYPE_VARINT) {
                        return reader.readVarint() != 0;
                    }
                    reader.skip(wireType);
                    break;
                default:
                    reader.skip(wireType);
                    break;
            }
        }

        return null;
    }

    /**
     * Minimal protobuf wire format reader.
     */
    private static class ProtobufReader {
        private final byte[] data;
        private int pos;

        ProtobufReader(byte[] data) {
            this.data = data;
            this.pos = 0;
        }

        boolean hasRemaining() {
            return pos < data.length;
        }

        int readTag() throws IOException {
            return (int) readVarint();
        }

        long readVarint() throws IOException {
            long result = 0;
            int shift = 0;
            while (pos < data.length) {
                byte b = data[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift >= 64) {
                    throw new IOException("Varint too long");
                }
            }
            throw new IOException("Truncated varint");
        }

        byte[] readBytes() throws IOException {
            int length = (int) readVarint();
            if (length < 0 || pos + length > data.length) {
                throw new IOException("Invalid length: " + length);
            }
            byte[] result = new byte[length];
            System.arraycopy(data, pos, result, 0, length);
            pos += length;
            return result;
        }

        String readString() throws IOException {
            return new String(readBytes(), StandardCharsets.UTF_8);
        }

        float readFloat() throws IOException {
            if (pos + 4 > data.length) throw new IOException("Truncated float");
            int bits = (data[pos] & 0xFF)
                    | ((data[pos + 1] & 0xFF) << 8)
                    | ((data[pos + 2] & 0xFF) << 16)
                    | ((data[pos + 3] & 0xFF) << 24);
            pos += 4;
            return Float.intBitsToFloat(bits);
        }

        double readDouble() throws IOException {
            if (pos + 8 > data.length) throw new IOException("Truncated double");
            long bits = 0;
            for (int i = 0; i < 8; i++) {
                bits |= (long) (data[pos + i] & 0xFF) << (i * 8);
            }
            pos += 8;
            return Double.longBitsToDouble(bits);
        }

        void skip(int wireType) throws IOException {
            switch (wireType) {
                case WIRETYPE_VARINT:
                    readVarint();
                    break;
                case WIRETYPE_64BIT:
                    if (pos + 8 > data.length) throw new IOException("Truncated 64-bit");
                    pos += 8;
                    break;
                case WIRETYPE_LENGTH_DELIMITED:
                    int len = (int) readVarint();
                    if (pos + len > data.length) throw new IOException("Truncated bytes");
                    pos += len;
                    break;
                case WIRETYPE_32BIT:
                    if (pos + 4 > data.length) throw new IOException("Truncated 32-bit");
                    pos += 4;
                    break;
                default:
                    throw new IOException("Unknown wire type: " + wireType);
            }
        }
    }
}
