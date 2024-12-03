package ext;

import arc.graphics.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import arc.util.serialization.Jval.*;
import mindustry.mod.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.text.*;
import java.util.*;

import static mindustry.Vars.*;

public class Extractor extends Mod{
    public Extractor(){
        if(headless) throw new UnsupportedOperationException("This mod doesn't work in headless, since it requires an OpenGL context.");
    }

    public static void extract(Mesh mesh, ExtractOptions options) throws IOException{
        if(!options.output.createNewFile()){
            throw new IOException("output file already exists");
        }

        int vertexCount = mesh.getNumVertices(), indexCount = mesh.getNumIndices();
        if(vertexCount <= 0) throw new IOException("mesh must have at least one vertex");

        try(var output = new FileOutputStream(options.output, true);
            var json = new ByteArrayOutputStream();
            var jsonBuf = new EndianOutputStream(json, bufferSize, ByteOrder.LITTLE_ENDIAN);
            var bin = new ByteArrayOutputStream();
            var binBuf = new EndianOutputStream(bin, bufferSize, ByteOrder.LITTLE_ENDIAN)
        ){
            output.write(new byte[]{
                'g', 'l', 'T', 'F',
                0x02, 0x00, 0x00, 0x00
            });

            Jval accessors, buffer, bufferViews, primitive, attributes, gltf = Jval.newObject()
                .put("accessors", accessors = Jval.newArray())
                .put("asset", Jval.newObject()
                    .put("version", "2.0"))
                .put("buffers", Jval.newArray().add(buffer = Jval.newObject()))
                .put("bufferViews", bufferViews = Jval.newArray())
                .put("meshes", Jval.newArray()
                    .add(Jval.newObject()
                        .put("primitives", Jval.newArray()
                            .add((primitive = Jval.newObject())
                                .put("attributes", attributes = Jval.newObject())
                                .put("mode", options.renderMode)))
                        ))
                .put("nodes", Jval.newArray()
                    .add(Jval.newObject()
                        .put("mesh", 0)))
                .put("scene", 0)
                .put("scenes", Jval.newArray()
                    .add(Jval.newObject()
                        .put("nodes", Jval.newArray()
                            .add(0))));

            int accessorOffset = 0, accessorIndex = 0;
            for(var attrib : mesh.attributes){
                Jval accessor;
                accessors.add(accessor = Jval.newObject()
                    .put("bufferView", 0)
                    .put("byteOffset", accessorOffset)
                    .put("componentType", attrib.type)
                    .put("normalized", attrib.normalized)
                    .put("count", vertexCount)
                    .put("type", switch(attrib.components){
                        case 1 -> "SCALAR";
                        case 2 -> "VEC2";
                        case 3 -> "VEC3";
                        case 4 -> "VEC4";
                        default -> throw new AssertionError();
                    }));

                if(attrib.alias.equals(VertexAttribute.position3.alias)){
                    var vertices = mesh.getVerticesBuffer();
                    vertices.position(0);

                    float[] pos = new float[attrib.components];
                    float[] min = new float[attrib.components], max = new float[attrib.components];
                    Arrays.fill(min, Float.POSITIVE_INFINITY);
                    Arrays.fill(max, Float.NEGATIVE_INFINITY);

                    for(int i = 0; i < vertexCount; i++){
                        vertices.position(mesh.vertexSize / Float.BYTES * i + accessorOffset);
                        vertices.get(pos);

                        for(int j = 0; j < attrib.components; j++){
                            if(min[j] > pos[j]) min[j] = pos[j];
                            if(max[j] < pos[j]) max[j] = pos[j];
                        }
                    }

                    Jval minJson = Jval.newArray(), maxJson = Jval.newArray();
                    for(int i = 0; i < attrib.components; i++){
                        minJson.add(Jval.valueOf(min[i]));
                        maxJson.add(Jval.valueOf(max[i]));
                    }

                    accessor.put("min", minJson).put("max", maxJson);
                }

                accessorOffset += attrib.size;
                attributes.put(options.attributeMap.get(attrib.alias, attrib.alias), accessorIndex++);
            }

            var vertices = mesh.getVerticesBuffer();
            vertices.position(0);
            binBuf.writeFloatBuffer(vertices);
            vertices.position(0);

            binBuf.flush();
            int binLen = bin.size();
            bufferViews.add(Jval.newObject()
                .put("buffer", 0)
                .put("byteOffset", 0)
                .put("byteLength", binLen)
                .put("byteStride", mesh.vertexSize)
                .put("target", Gl.arrayBuffer));

            if(indexCount > 0){
                var indices = mesh.getIndicesBuffer();
                indices.position(0);
                binBuf.writeShortBuffer(indices);
                indices.position(0);

                binBuf.flush();
                int oldBinLen = binLen;
                binLen = bin.size();
                bufferViews.add(Jval.newObject()
                    .put("buffer", 0)
                    .put("byteOffset", oldBinLen)
                    .put("byteLength", binLen - oldBinLen)
                    .put("target", Gl.elementArrayBuffer));

                accessors.add(Jval.newObject()
                    .put("bufferView", 1)
                    .put("componentType", Gl.unsignedShort)
                    .put("count", indexCount)
                    .put("type", "SCALAR"));
                primitive.put("indices", accessorIndex);
            }

            if(binLen % 4 != 0){
                for(int i = 0, pad = 4 - (binLen % 4); i < pad; i++){
                    binBuf.writeByte(0x00);
                }

                binBuf.flush();
                binLen = bin.size();
            }
            buffer.put("byteLength", binLen);

            try(var writer = new OutputStreamWriter(jsonBuf, StandardCharsets.UTF_8)){
                gltf.writeTo(writer, Jformat.plain);
            }
            jsonBuf.flush();

            int jsonLen = json.size();
            if(jsonLen % 4 != 0){
                for(int i = 0, pad = 4 - (jsonLen % 4); i < pad; i++){
                    jsonBuf.writeByte(0x20);
                }

                jsonBuf.flush();
                jsonLen = json.size();
            }

            int totalLen = 12 + 8 + jsonLen + 8 + binLen;

            output.write(new byte[]{
                (byte)(totalLen & 0xff), (byte)((totalLen >>> 8) & 0xff), (byte)((totalLen >>> 16) & 0xff), (byte)((totalLen >>> 24) & 0xff),
                (byte)(jsonLen & 0xff), (byte)((jsonLen >>> 8) & 0xff), (byte)((jsonLen >>> 16) & 0xff), (byte)((jsonLen >>> 24) & 0xff),
                'J', 'S', 'O', 'N'
            });
            output.write(json.toByteArray());

            output.write(new byte[]{
                (byte)(binLen & 0xff), (byte)((binLen >>> 8) & 0xff), (byte)((binLen >>> 16) & 0xff), (byte)((binLen >>> 24) & 0xff),
                'B', 'I', 'N', 0x00
            });
            output.write(bin.toByteArray());
        }
    }

    public static class ExtractOptions{
        public File output = new File("ModelExtractor " + new SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(new Date(Time.millis())) + ".glb");

        public int renderMode = Gl.triangles;
        public StringMap attributeMap = StringMap.of(
            VertexAttribute.color.alias, "COLOR_0",
            VertexAttribute.position3.alias, "POSITION",
            VertexAttribute.normal.alias, "NORMAL",
            VertexAttribute.texCoords.alias, "TEXCOORD_0"
        );
    }

    public static class EndianOutputStream extends OutputStream implements DataOutput, AutoCloseable{
        private final OutputStream stream;
        private final byte[] data;
        private final ByteBuffer buffer;

        public EndianOutputStream(OutputStream stream, int bufferSize, ByteOrder order){
            if(bufferSize < Long.BYTES) bufferSize = Long.BYTES;

            this.stream = stream;
            data = new byte[bufferSize];
            buffer = ByteBuffer.wrap(data, 0, bufferSize);
            buffer.order(order);
        }

        protected void ensureSize(int add) throws IOException{
            if(buffer.position() > buffer.capacity() - add) flush();
        }

        @Override
        public void flush() throws IOException{
            buffer.flip();
            stream.write(data, 0, buffer.limit());
            buffer.clear();
        }

        @Override
        public void close() throws IOException{
            flush();
        }

        public void writeFloatBuffer(FloatBuffer src) throws IOException{
            int add = src.remaining();
            while(add > 0){
                var dst = buffer.asFloatBuffer();
                int write = Math.min(add, dst.remaining());
                if(write == 0){
                    flush();
                    continue;
                }

                src.limit(src.position() + write);
                dst.put(src);
                buffer.position(buffer.position() + write * Float.BYTES);
                add -= write;
            }
        }

        public void writeShortBuffer(ShortBuffer src) throws IOException{
            int add = src.remaining();
            while(add > 0){
                var dst = buffer.asShortBuffer();
                int write = Math.min(add, dst.remaining());
                if(write == 0){
                    flush();
                    continue;
                }

                src.limit(src.position() + write);
                dst.put(src);
                buffer.position(buffer.position() + write * Short.BYTES);
                add -= write;
            }
        }

        @Override
        public void writeBoolean(boolean v) throws IOException{
            writeByte(v ? 1 : 0);
        }

        @Override
        public void writeByte(int v) throws IOException{
            ensureSize(Byte.BYTES);
            buffer.put((byte)(v & 0xff));
        }

        @Override
        public void writeShort(int v) throws IOException{
            ensureSize(Short.BYTES);
            buffer.putShort((short)(v & 0xffff));
        }

        @Override
        public void writeChar(int v) throws IOException{
            ensureSize(Character.BYTES);
            buffer.putChar((char)(v & 0xffff));
        }

        @Override
        public void writeInt(int v) throws IOException{
            ensureSize(Integer.BYTES);
            buffer.putInt(v);
        }

        @Override
        public void writeLong(long v) throws IOException{
            ensureSize(Long.BYTES);
            buffer.putLong(v);
        }

        @Override
        public void writeFloat(float v) throws IOException{
            writeInt(Float.floatToRawIntBits(v));
        }

        @Override
        public void writeDouble(double v) throws IOException{
            writeLong(Double.doubleToRawLongBits(v));
        }

        @Override
        public void writeBytes(String s) throws IOException{
            for(int i = 0, len = s.length(); i < len; i++){
                writeByte(s.charAt(i));
            }
        }

        @Override
        public void writeChars(String s) throws IOException{
            for(int i = 0, len = s.length(); i < len; i++){
                writeChar(s.charAt(i));
            }
        }

        @Override
        public void writeUTF(String s) throws IOException{
            int strLen = s.length(), utfLen = strLen;
            for(int i = 0; i < strLen; i++){
                char c = s.charAt(i);
                if(c >= 0x80 || c == 0) utfLen += (c >= 0x800) ? 2 : 1;
            }

            if(utfLen > 65536 || utfLen < strLen) throw new UTFDataFormatException("encoded string too long");

            writeByte((utfLen >>> 8) & 0xff);
            writeByte(utfLen & 0xff);

            int i;
            for(i = 0; i < strLen; i++) {
                char c = s.charAt(i);
                if(c >= 0x80 || c == 0) break;

                writeByte(c);
            }

            for(; i < strLen; i++){
                char c = s.charAt(i);
                if(c < 0x80 && c != 0){
                    writeByte(c);
                }else if(c >= 0x800){
                    writeByte(0xe0 | ((c >> 12) & 0x0f));
                    writeByte(0x80 | ((c >> 6) & 0x3f));
                    writeByte(0x80 | (c & 0x3f));
                }else{
                    writeByte(0xc0 | ((c >> 6) & 0x1f));
                    writeByte(0x80 | (c & 0x3f));
                }
            }
        }

        @Override
        public void write(int b) throws IOException{
            writeByte(b);
        }
    }
}
