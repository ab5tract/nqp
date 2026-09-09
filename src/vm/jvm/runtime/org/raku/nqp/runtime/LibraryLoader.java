package org.raku.nqp.runtime;

import java.io.File;
import java.io.IOException;
import java.io.EOFException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.BufferUnderflowException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4DecompressorWithLength;

public class LibraryLoader {
    private static Map<String,Class<?>> sharedClasses = new ConcurrentHashMap< >();

    public static void load(ThreadContext tc, String filename) {
        // Don't load the same thing multiple times.
        if (!tc.gc.byteClassLoader.addRef(filename))
            return;
        else try {
            // Read in class data.
            File file = new File(filename);
            if (!file.exists() && filename.equals("ModuleLoader.class")) {
                /* We special case the initial ModuleLoader loading. */
                String[] cps = System.getProperty("java.class.path").split("[:;]");
                for (int i = 0; i < cps.length; i++) {
                    file = new File(cps[i] + "/" + filename);
                    if (file.exists()) {
                        filename = cps[i] + "/" + filename;
                        break;
                    }
                    file = new File(cps[i] + "/ModuleLoader.jar");
                    if (file.exists()) {
                        filename = cps[i] + "/ModuleLoader.jar";
                        break;
                    }
                }
            }

            if (org.raku.nqp.runtime.unit.UnitLoader.isUnitFile(filename)) {
                try {
                    org.raku.nqp.runtime.unit.UnitLoader.loadAndRun(tc, filename, tc.gc.sharingHint);
                }
                catch (ControlException e) { throw e; }
                catch (IOException | IllegalStateException | IllegalArgumentException e) {
                    throw ExceptionHandling.dieInternal(tc, e);
                }
            }
            else
                resolveClass(tc, loadFile(filename, tc.gc.byteClassLoader, tc.gc.sharingHint));
        }
        catch (IOException | IllegalArgumentException | ClassNotFoundException e) {
            throw ExceptionHandling.dieInternal(tc, e);
        }
    }

    public static void load(ThreadContext tc, byte[] buffer) {
        ByteBuffer bb;
        try {
            bb = ByteBuffer.allocate(buffer.length);
            bb.put(buffer);
            bb.rewind();
        }
        catch (BufferOverflowException | IllegalArgumentException e) {
            throw ExceptionHandling.dieInternal(tc, e);
        }
        load(tc, bb);
    }

    public static void load(ThreadContext tc, ByteBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.duplicate().get(bytes);
            if (org.raku.nqp.runtime.unit.UnitZip.isUnit(bytes)) {
                try {
                    org.raku.nqp.runtime.unit.UnitLoader.loadAndRun(tc, bytes);
                }
                catch (ControlException e) { throw e; }
                catch (IllegalStateException | IllegalArgumentException e) {
                    throw ExceptionHandling.dieInternal(tc, e);
                }
            }
            else
                resolveClass(tc, loadJar(buffer, tc.gc.byteClassLoader));
        }
        catch (IOException | IllegalArgumentException | ClassNotFoundException e) {
            throw ExceptionHandling.dieInternal(tc, e);
        }
    }

    public static Class<?> loadFile(
        String fn,
        ByteClassLoader parent,
        boolean shared
    ) throws IOException, IllegalArgumentException, ClassNotFoundException {
        if (shared)
            return sharedClasses.computeIfAbsent(fn, (k) -> {
                try {
                    return loadFile(k, parent, false);
                }
                catch (IOException | IllegalArgumentException | ClassNotFoundException e) {
                    return null;
                }
            });
        else try (RandomAccessFile fh = new RandomAccessFile(fn, "r")) {
            switch (fh.readInt()) {
                case 0xCAFEBABE: // classfile
                    return loadClass(fn, parent);
                default: // JAR
                    // The manifest alone gives us at least an int's width. Let
                    // the JarInputStream decide if this is a JAR or not.
                    return loadJar(fn, parent);
            }
        }
        catch (EOFException e) {
            throw new IllegalArgumentException("Unrecognized bytecode format in " + fn);
        }

    }

    public static Class<?> loadClass(String fn, ByteClassLoader parent) throws IOException, ClassNotFoundException {
        return new FileClassLoader(fn, parent).loadSerialClass(null);
    }

    public static Class<?> loadJar(String fn, ByteClassLoader parent) throws IOException, ClassNotFoundException {
        return new JarFileClassLoader(fn, parent).loadSerialClass(null);
    }

    public static Class<?> loadJar(
        ByteBuffer buffer,
        ByteClassLoader parent
    ) throws IOException, IllegalArgumentException, ClassNotFoundException {
        String name = null;
        ByteBuffer classfile = null;
        ByteBuffer serial = null;
        ByteBuffer progs = null;
        Map<String, ByteBuffer> nested = new HashMap<>();
        try (JarInputStream jis = new JarInputStream(new ByteBufferedInputStream(buffer))) {
            JarEntry je;
            while ((je = jis.getNextJarEntry()) != null) {
                String jf = je.getName();
                if (jf.endsWith(".class") && classfile == null) {
                    name = je.getComment();
                    classfile = readToHeapBuffer(jis);
                }
                else if (jf.endsWith(".class"))
                    /* A nested unit riding along; served on demand. */
                    nested.put(jf.substring(0, jf.length() - ".class".length()),
                        readToHeapBuffer(jis));
                else if (jf.endsWith(".serialized.lz4") && serial == null)
                    serial = readToHeapBuffer(jis);
                else if (jf.endsWith(".serialized") && serial == null)
                    serial = readToHeapBuffer(jis);
                else if (jf.endsWith(".codeprograms.lz4") && progs == null)
                    /* The unit's engine programs (see CodeEngines.codeRunIdx). */
                    progs = readToHeapBuffer(jis);
                else
                    throw new IllegalArgumentException("Bytecode jar contains unexpected file " + jf);
            }
        }
        if (classfile == null)
            throw new IllegalArgumentException("Bytecode jar lacks class file");
        if (serial == null)
            throw new IllegalArgumentException("Bytecode jar lacks serialization file");
        return new MemoryClassLoader(classfile, serial, progs, nested, parent).loadSerialClass(name);
    }

    public static void resolveClass(ThreadContext tc, Class<?> c) {
        try {
            CompilationUnit cu = (CompilationUnit)c.getDeclaredConstructor().newInstance();
            cu.shared = tc.gc.sharingHint;
            cu.initializeCompilationUnit(tc);
            cu.runLoadIfAvailable(tc);
        }
        catch (ReflectiveOperationException e) {
            throw ExceptionHandling.dieInternal(tc, e);
        }
    }

    /* Road-agnostic app load for entry points (runner main, eval server):
     * the unit is initialized (deserialized) but its load block is not run;
     * an entry block does that itself. */
    public static CompilationUnit loadApp(ThreadContext tc, String path, boolean shared) {
        if (org.raku.nqp.runtime.unit.UnitLoader.isUnitFile(path, shared)) {
            try {
                return org.raku.nqp.runtime.unit.UnitLoader.loadUnit(tc, path, shared);
            }
            catch (ControlException e) { throw e; }
            catch (IOException | IllegalStateException | IllegalArgumentException e) {
                throw ExceptionHandling.dieInternal(tc, e);
            }
        }
        try {
            return CompilationUnit.setupCompilationUnit(tc, loadFile(path, tc.gc.byteClassLoader, shared), shared);
        }
        catch (IOException | IllegalArgumentException | ReflectiveOperationException e) {
            throw ExceptionHandling.dieInternal(tc, e);
        }
    }

    /* Warms whichever road the path takes, so a server pays for parsing once. */
    public static void prime(String path, ByteClassLoader loader) throws IOException, ClassNotFoundException {
        if (org.raku.nqp.runtime.unit.UnitLoader.isUnitFile(path, true))
            org.raku.nqp.runtime.unit.UnitLoader.record(path, true);
        else
            loadFile(path, loader, true);
    }

    public static ByteBuffer readToHeapBuffer(InputStream is) throws IOException {
        return ByteBuffer.wrap(is.readAllBytes());
    }

    /* This used to be fastestInstance(), which selects lz4's
     * sun.misc.Unsafe fast path. Switched to the pure-Java safeInstance()
     * to future-proof against the deprecation-for-removal of Unsafe
     * (JEP 498: JDK 24+ warns on every process start, and later releases
     * will refuse outright). */
    private static final LZ4DecompressorWithLength lz4 =
        new LZ4DecompressorWithLength(LZ4Factory.safeInstance().fastDecompressor());

    public static ByteBuffer readToHeapBufferLz4(InputStream is) throws IOException {
        return ByteBuffer.wrap(lz4.decompress(is.readAllBytes()));
    }

    private static abstract class SerialClassLoader extends ClassLoader {
        protected SerialClassLoader(ByteClassLoader parent) {
            super(parent);
        }

        protected abstract Class<?> findSerialClass(String name) throws ClassNotFoundException;

        protected synchronized Class<?> loadSerialClass(String name) throws ClassNotFoundException {
            Class<?> klass = name == null ? null : findLoadedClass(name);
            if (klass == null) {
                klass = getRead(name);
                if (klass == null) {
                    setRead(name, klass = findSerialClass(name));
                    if (klass == null)
                        throw new ClassNotFoundException(name);
                }
            }
            resolveClass(klass);
            return klass;
        }

        private Class<?> getRead(String name) {
            ByteClassLoader parent = (ByteClassLoader)getParent();
            return parent == null ? null : parent.getRead(this, name);
        }

        private Class<?> setRead(String name, Class<?> klass) {
            ByteClassLoader parent = (ByteClassLoader)getParent();
            return parent == null ? null : parent.setRead(this, name, klass);
        }
    }

    private static abstract class StreamClassLoader extends SerialClassLoader {
        protected final String filename;
        protected InputStream classfile;

        protected StreamClassLoader(String filename, InputStream classfile, ByteClassLoader parent) {
            super(parent);
            this.filename = filename;
            this.classfile = classfile;
        }

        public String getFileName() {
            return filename;
        }

        protected Class<?> findSerialClass(String name) throws ClassNotFoundException {
            try {
                byte[] buffer = classfile.readAllBytes();
                return defineClass(name, buffer, 0, buffer.length);
            }
            catch (IOException | IndexOutOfBoundsException e) {
                throw new ClassNotFoundException("could not read serial class" + name, e);
            }
            catch (NoClassDefFoundError e) {
                throw new ClassNotFoundException("serial class not named " + name, e);
            }
            catch (SecurityException e) {
                throw new ClassNotFoundException("serial class is insecurely named " + name, e);
            }
        }
    }

    private static class FileClassLoader extends StreamClassLoader {
        static { ClassLoader.registerAsParallelCapable(); }

        protected FileClassLoader(String filename, ByteClassLoader parent) {
            super(filename, null, parent);
        }

        @Override
        protected Class<?> findSerialClass(String name) throws ClassNotFoundException {
            try {
                classfile = Files.newInputStream(Paths.get(filename), StandardOpenOption.READ);
                Class<?> klass = super.findSerialClass(
                    name == null ? filename.replace(File.pathSeparatorChar, '.') : name);
                classfile.close();
                classfile = null;
                return klass;
            }
            catch (InvalidPathException e) {
                throw new ClassNotFoundException("no such classfile " + filename, e);
            }
            catch (IllegalArgumentException | UnsupportedOperationException | IOException e) {
                throw new ClassNotFoundException("cannot read " + filename, e);
            }
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            return null;
        }
    }

    private static class JarFileClassLoader extends StreamClassLoader {
        static { ClassLoader.registerAsParallelCapable(); }

        protected JarFileClassLoader(String filename, ByteClassLoader parent) {
            super(filename, null, parent);
        }

        @Override
        protected Class<?> findSerialClass(String name) throws ClassNotFoundException {
            try (JarFile jar = new JarFile(filename)) {
                JarEntry entry = null;
                for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); )
                    if ((entry = entries.nextElement()).getName().endsWith(".class"))
                        break;
                if (entry == null)
                    throw new ClassNotFoundException(name + " in " + filename);
                classfile = jar.getInputStream(entry);
                return super.findSerialClass(
                    name == null ? entry.getComment() : name);
            }
            catch (IOException | ClassNotFoundException e) {
                throw new ClassNotFoundException(name + " in " + filename, e);
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            /* A nested unit embedded alongside the main class. */
            try (JarFile jar = new JarFile(filename)) {
                JarEntry entry = jar.getJarEntry(name + ".class");
                if (entry == null)
                    throw new ClassNotFoundException(name + " in " + filename);
                byte[] buffer = jar.getInputStream(entry).readAllBytes();
                return defineClass(name, buffer, 0, buffer.length);
            }
            catch (IOException e) {
                throw new ClassNotFoundException(name + " in " + filename, e);
            }
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            try {
                JarFile jar = new JarFile(filename);
                JarEntry entry = jar.getJarEntry(name);
                return entry == null ? null : jar.getInputStream(entry);
            }
            catch (IOException e) {
                return null;
            }
        }
    }

    private static class MemoryClassLoader extends SerialClassLoader {
        static { ClassLoader.registerAsParallelCapable(); }

        private final WeakReference<ByteBuffer> classfile;
        private final ByteBuffer serial;
        private final ByteBuffer progs;
        private final Map<String, ByteBuffer> nested;

        protected MemoryClassLoader(ByteBuffer classfile, ByteBuffer serial, ByteClassLoader parent) {
            this(classfile, serial, null, new HashMap<>(), parent);
        }

        protected MemoryClassLoader(ByteBuffer classfile, ByteBuffer serial, ByteBuffer progs, Map<String, ByteBuffer> nested, ByteClassLoader parent) {
            super(parent);
            this.classfile = new WeakReference(classfile);
            this.serial = serial;
            this.progs = progs;
            this.nested = nested;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            ByteBuffer bytes = nested.get(name);
            if (bytes == null)
                throw new ClassNotFoundException(name);
            try {
                return defineClass(name, bytes, null);
            }
            catch (NoClassDefFoundError | IndexOutOfBoundsException | SecurityException e) {
                throw new ClassNotFoundException("could not define nested class " + name, e);
            }
        }

        protected Class<?> findSerialClass(String name) throws ClassNotFoundException {
            ByteBuffer classfile = this.classfile.get();
            if (classfile == null) {
                throw new ClassNotFoundException("in-memory classfile missing; probably already loaded " + name);
            }
            else try {
                return defineClass(name, classfile, null);
            }
            catch (NoClassDefFoundError e) {
                throw new ClassNotFoundException("classfile not named " + name, e);
            }
            catch (IndexOutOfBoundsException e) {
                throw new ClassNotFoundException("could not read classfile " + name, e);
            }
            catch (SecurityException e) {
                throw new ClassNotFoundException("classfile is insecurely named " + name, e);
            }
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            /* Serve by suffix: the serialized SC and the engine-program
             * table are distinct sidecars of the same unit. */
            ByteBuffer which = name != null && name.endsWith(".codeprograms.lz4")
                ? progs : serial;
            if (which == null)
                return null;
            else try {
                return ByteBufferedInputStream.copy(which);
            }
            catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private static class ByteBufferedInputStream extends InputStream {
        private ByteBuffer bb;

        public ByteBufferedInputStream(ByteBuffer bb) {
            this.bb = bb;
        }

        public static ByteBufferedInputStream copy(ByteBuffer src) throws IllegalArgumentException {
            int offset = src.position();
            ByteBuffer bb = ByteBuffer.allocate(src.capacity() - offset);
            bb.put(src);
            bb.rewind();
            src.position(offset);
            return new ByteBufferedInputStream(bb);
        }

        public static InputStream nullInputStream() {
            return new ByteBufferedInputStream(null);
        }

        private int get() throws IOException {
            try {
                return bb.get();
            }
            catch (BufferUnderflowException e) {
                throw new IOException(e);
            }
        }

        private void get(byte[] dst) throws IOException {
            try {
                bb.get(dst);
            }
            catch (BufferUnderflowException e) {
                throw new IOException(e);
            }
        }

        @Override
        public int read() throws IOException {
            return bb != null && bb.hasRemaining() ? get() : -1;
        }

        @Override
        public int read(byte[] dst) throws IOException {
            if (bb == null)
                return -1;

            int length = Math.min(dst.length, available());
            if (length <= 0)
                return 0;

            byte[] src = new byte[length];
            get(src);
            System.arraycopy(src, 0, dst, 0, length);
            return length;
        }

        @Override
        public int read(byte[] dst, int offset, int length) throws IOException {
            if (bb == null)
                return -1;

            length = Math.min(length, available());
            if (length <= 0)
                return 0;

            byte[] src = new byte[length];
            get(src);
            System.arraycopy(src, 0, dst, offset, length);
            return length;
        }

        @Override
        public int readNBytes(byte[] dst, int offset, int length) throws IOException {
            if (bb == null)
                return -1;

            length = Math.min(length, available());
            if (length <= 0)
                return 0;

            byte[] src = new byte[length];
            get(src);
            System.arraycopy(src, 0, dst, offset, length);
            return length;
        }

        @Override
        public byte[] readAllBytes() throws IOException {
            byte[] dst = new byte[available()];
            if (dst.length > 0)
                get(dst);
            return dst;
        }

        @Override
        public long skip(long length) {
            if (bb == null)
                return 0;

            int offset = bb.position();
            length = Long.min(length, bb.capacity() - offset);
            bb.position(offset + (int)length);
            return length;
        }

        @Override
        public int available() {
            return bb == null ? 0 : (bb.capacity() - bb.position());
        }

        @Override
        public void reset() {
            if (bb != null)
                bb.rewind();
        }

        @Override
        public void close() {
            this.bb = null;
        }
    }
}
