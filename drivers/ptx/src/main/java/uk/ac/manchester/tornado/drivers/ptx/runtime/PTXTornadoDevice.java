/*
 * This file is part of Tornado: A heterogeneous programming framework:
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2020, APT Group, Department of Computer Science,
 * School of Engineering, The University of Manchester. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */
package uk.ac.manchester.tornado.drivers.ptx.runtime;

import static uk.ac.manchester.tornado.drivers.ptx.graal.PTXCodeUtil.buildKernelName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import jdk.vm.ci.meta.ResolvedJavaMethod;
import uk.ac.manchester.tornado.api.TornadoTargetDevice;
import uk.ac.manchester.tornado.api.common.Access;
import uk.ac.manchester.tornado.api.common.Event;
import uk.ac.manchester.tornado.api.common.SchedulableTask;
import uk.ac.manchester.tornado.api.enums.TornadoDeviceType;
import uk.ac.manchester.tornado.api.enums.TornadoVMBackendType;
import uk.ac.manchester.tornado.api.exceptions.TornadoBailoutRuntimeException;
import uk.ac.manchester.tornado.api.exceptions.TornadoInternalError;
import uk.ac.manchester.tornado.api.exceptions.TornadoRuntimeException;
import uk.ac.manchester.tornado.api.mm.ObjectBuffer;
import uk.ac.manchester.tornado.api.mm.TornadoDeviceObjectState;
import uk.ac.manchester.tornado.api.mm.TornadoMemoryProvider;
import uk.ac.manchester.tornado.api.profiler.ProfilerType;
import uk.ac.manchester.tornado.api.profiler.TornadoProfiler;
import uk.ac.manchester.tornado.api.type.annotations.Vector;
import uk.ac.manchester.tornado.drivers.common.TornadoBufferProvider;
import uk.ac.manchester.tornado.drivers.ptx.PTX;
import uk.ac.manchester.tornado.drivers.ptx.PTXDevice;
import uk.ac.manchester.tornado.drivers.ptx.PTXDeviceContext;
import uk.ac.manchester.tornado.drivers.ptx.PTXDriver;
import uk.ac.manchester.tornado.drivers.ptx.graal.PTXCodeUtil;
import uk.ac.manchester.tornado.drivers.ptx.graal.PTXProviders;
import uk.ac.manchester.tornado.drivers.ptx.graal.backend.PTXBackend;
import uk.ac.manchester.tornado.drivers.ptx.graal.compiler.PTXCompilationResult;
import uk.ac.manchester.tornado.drivers.ptx.graal.compiler.PTXCompiler;
import uk.ac.manchester.tornado.drivers.ptx.mm.PTXByteArrayWrapper;
import uk.ac.manchester.tornado.drivers.ptx.mm.PTXCharArrayWrapper;
import uk.ac.manchester.tornado.drivers.ptx.mm.PTXDoubleArrayWrapper;
import uk.ac.manchester.tornado.drivers.ptx.mm.PTXFloatArrayWrapper;
import uk.ac.manchester.tornado.drivers.ptx.mm.PTXIntArrayWrapper;
import uk.ac.manchester.tornado.drivers.ptx.mm.PTXLongArrayWrapper;
import uk.ac.manchester.tornado.drivers.ptx.mm.PTXMultiDimArrayWrapper;
import uk.ac.manchester.tornado.drivers.ptx.mm.PTXObjectWrapper;
import uk.ac.manchester.tornado.drivers.ptx.mm.PTXShortArrayWrapper;
import uk.ac.manchester.tornado.drivers.ptx.mm.PTXVectorWrapper;
import uk.ac.manchester.tornado.runtime.TornadoCoreRuntime;
import uk.ac.manchester.tornado.runtime.common.DeviceObjectState;
import uk.ac.manchester.tornado.runtime.common.KernelArgs;
import uk.ac.manchester.tornado.runtime.common.RuntimeUtilities;
import uk.ac.manchester.tornado.runtime.common.Tornado;
import uk.ac.manchester.tornado.runtime.common.TornadoAcceleratorDevice;
import uk.ac.manchester.tornado.runtime.common.TornadoInstalledCode;
import uk.ac.manchester.tornado.runtime.common.TornadoSchedulingStrategy;
import uk.ac.manchester.tornado.runtime.sketcher.Sketch;
import uk.ac.manchester.tornado.runtime.sketcher.TornadoSketcher;
import uk.ac.manchester.tornado.runtime.tasks.CompilableTask;
import uk.ac.manchester.tornado.runtime.tasks.PrebuiltTask;
import uk.ac.manchester.tornado.runtime.tasks.meta.TaskMetaData;

public class PTXTornadoDevice implements TornadoAcceleratorDevice {

    private static final boolean BENCHMARKING_MODE = Boolean.parseBoolean(System.getProperties().getProperty("tornado.benchmarking", "False"));
    private static PTXDriver driver = null;
    private final PTXDevice device;
    private final int deviceIndex;

    public PTXTornadoDevice(final int deviceIndex) {
        this.deviceIndex = deviceIndex;
        driver = TornadoCoreRuntime.getTornadoRuntime().getDriver(PTXDriver.class);
        if (driver == null) {
            throw new RuntimeException("TornadoVM PTX Driver not found");
        }
        device = PTX.getPlatform().getDevice(deviceIndex);
    }

    @Override
    public TornadoSchedulingStrategy getPreferredSchedule() {
        return TornadoSchedulingStrategy.PER_ITERATION;
    }

    @Override
    public KernelArgs createCallWrapper(int numArgs) {
        return getDeviceContext().getMemoryManager().createCallWrapper(numArgs);
    }

    @Override
    public ObjectBuffer createOrReuseAtomicsBuffer(int[] arr) {
        return null;
    }

    @Override
    public int[] checkAtomicsForTask(SchedulableTask task) {
        Tornado.debug("[PTX] Atomics not implemented ! Returning null");
        return null;
    }

    @Override
    public int[] checkAtomicsForTask(SchedulableTask task, int[] array, int paramIndex, Object value) {
        return null;
    }

    @Override
    public int[] updateAtomicRegionAndObjectState(SchedulableTask task, int[] array, int paramIndex, Object value, DeviceObjectState objectState) {
        return null;
    }

    @Override
    public int getAtomicsGlobalIndexForTask(SchedulableTask task, int paramIndex) {
        return -1;
    }

    @Override
    public boolean checkAtomicsParametersForTask(SchedulableTask task) {
        return false;
    }

    @Override
    public TornadoInstalledCode installCode(SchedulableTask task) {
        if (task instanceof CompilableTask) {
            return compileTask(task);
        } else if (task instanceof PrebuiltTask) {
            return compilePreBuiltTask(task);
        }
        TornadoInternalError.shouldNotReachHere("task of unknown type: " + task.getClass().getSimpleName());
        return null;
    }

    private TornadoInstalledCode compileTask(SchedulableTask task) {
        TornadoProfiler profiler = task.getProfiler();
        final PTXDeviceContext deviceContext = getDeviceContext();

        final CompilableTask executable = (CompilableTask) task;
        final ResolvedJavaMethod resolvedMethod = TornadoCoreRuntime.getTornadoRuntime().resolveMethod(executable.getMethod());
        final Sketch sketch = TornadoSketcher.lookup(resolvedMethod, task.meta().getDriverIndex(), task.meta().getDeviceIndex());

        // copy meta data into task
        final TaskMetaData taskMeta = executable.meta();
        final Access[] sketchAccess = sketch.getArgumentsAccess();
        final Access[] taskAccess = taskMeta.getArgumentsAccess();
        System.arraycopy(sketchAccess, 0, taskAccess, 0, sketchAccess.length);

        try {
            PTXCompilationResult result;
            if (!deviceContext.isCached(resolvedMethod.getName(), executable)) {
                PTXProviders providers = (PTXProviders) getBackend().getProviders();
                // profiler
                profiler.registerBackend(taskMeta.getId(), taskMeta.getLogicDevice().getTornadoVMBackend().name());
                profiler.registerDeviceID(taskMeta.getId(), taskMeta.getLogicDevice().getDriverIndex() + ":" + taskMeta.getDeviceIndex());
                profiler.registerDeviceName(taskMeta.getId(), taskMeta.getLogicDevice().getPhysicalDevice().getDeviceName());
                profiler.start(ProfilerType.TASK_COMPILE_GRAAL_TIME, taskMeta.getId());
                result = PTXCompiler.compileSketchForDevice(sketch, executable, providers, getBackend(), executable.getProfiler());
                profiler.stop(ProfilerType.TASK_COMPILE_GRAAL_TIME, taskMeta.getId());
                profiler.sum(ProfilerType.TOTAL_GRAAL_COMPILE_TIME, profiler.getTaskTimer(ProfilerType.TASK_COMPILE_GRAAL_TIME, taskMeta.getId()));
            } else {
                result = new PTXCompilationResult(buildKernelName(resolvedMethod.getName(), executable), taskMeta);
            }

            profiler.start(ProfilerType.TASK_COMPILE_DRIVER_TIME, taskMeta.getId());
            TornadoInstalledCode installedCode = deviceContext.installCode(result, resolvedMethod.getName());
            profiler.stop(ProfilerType.TASK_COMPILE_DRIVER_TIME, taskMeta.getId());
            profiler.sum(ProfilerType.TOTAL_DRIVER_COMPILE_TIME, profiler.getTaskTimer(ProfilerType.TASK_COMPILE_DRIVER_TIME, taskMeta.getId()));
            return installedCode;
        } catch (Exception e) {
            System.err.println(e.getMessage());
            driver.fatal("unable to compile %s for device %s\n", task.getId(), getDeviceName());
            driver.fatal("exception occurred when compiling %s\n", ((CompilableTask) task).getMethod().getName());
            throw new TornadoBailoutRuntimeException("[Error During the Task Compilation] ", e);
        }
    }

    private TornadoInstalledCode compilePreBuiltTask(SchedulableTask task) {
        final PTXDeviceContext deviceContext = getDeviceContext();
        final PrebuiltTask executable = (PrebuiltTask) task;
        String functionName = buildKernelName(executable.getEntryPoint(), executable);
        if (deviceContext.isCached(executable.getEntryPoint(), executable)) {
            return deviceContext.getInstalledCode(functionName);
        }

        final Path path = Paths.get(executable.getFilename());
        TornadoInternalError.guarantee(path.toFile().exists(), "file does not exist: %s", executable.getFilename());
        try {
            byte[] source = Files.readAllBytes(path);
            source = PTXCodeUtil.getCodeWithAttachedPTXHeader(source, getBackend());
            return deviceContext.installCode(functionName, source, executable.getEntryPoint());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public boolean isFullJITMode(SchedulableTask task) {
        return true;
    }

    @Override
    public TornadoInstalledCode getCodeFromCache(SchedulableTask task) {
        String methodName;
        if (task instanceof PrebuiltTask) {
            PrebuiltTask prebuiltTask = (PrebuiltTask) task;
            methodName = prebuiltTask.getEntryPoint();
        } else {
            CompilableTask compilableTask = (CompilableTask) task;
            ResolvedJavaMethod resolvedMethod = TornadoCoreRuntime.getTornadoRuntime().resolveMethod(compilableTask.getMethod());
            methodName = resolvedMethod.getName();
        }
        String functionName = buildKernelName(methodName, task);
        return getDeviceContext().getInstalledCode(functionName);
    }

    private void checkBatchSize(long batchSize) {
        if (batchSize > 0) {
            throw new TornadoRuntimeException("[ERROR] Batch computation with non-arrays not supported yet.");
        }
    }

    private ObjectBuffer createDeviceBuffer(Class<?> type, Object arg, long batchSize) {
        ObjectBuffer result = null;
        if (type.isArray()) {

            if (!type.getComponentType().isArray()) {
                result = createArrayWrapper(type, getDeviceContext(), batchSize);
            } else {
                final Class<?> componentType = type.getComponentType();
                if (RuntimeUtilities.isPrimitiveArray(componentType)) {
                    result = createMultiArrayWrapper(componentType, type, batchSize);
                } else {
                    TornadoInternalError.unimplemented("multi-dimensional array of type %s", type.getName());
                }
            }
        } else if (!type.isPrimitive()) {
            if (arg.getClass().getAnnotation(Vector.class) != null) {
                result = new PTXVectorWrapper(getDeviceContext(), arg, batchSize);
            } else {
                result = new PTXObjectWrapper(getDeviceContext(), arg);
            }
        }

        TornadoInternalError.guarantee(result != null, "Unable to create buffer for object: " + type);
        return result;
    }

    @Override
    public int allocateBulk(Object[] objects, long batchSize, TornadoDeviceObjectState[] states) {
        TornadoBufferProvider bufferProvider = getDeviceContext().getBufferProvider();
        if (!bufferProvider.canAllocate(objects.length)) {
            bufferProvider.resetBuffers();
        }
        for (int i = 0; i < objects.length; i++) {
            allocate(objects[i], batchSize, states[i]);
        }
        return -1;
    }

    @Override
    public int allocate(Object object, long batchSize, TornadoDeviceObjectState state) {
        final ObjectBuffer buffer;
        if (!state.hasObjectBuffer() || !state.isLockedBuffer()) {
            TornadoInternalError.guarantee(state.isAtomicRegionPresent() || !state.hasObjectBuffer(), "A device memory leak might be occurring.");
            buffer = createDeviceBuffer(object.getClass(), object, batchSize);
            state.setObjectBuffer(buffer);
            buffer.allocate(object, batchSize);
        }

        final Class<?> type = object.getClass();
        if (!type.isArray()) {
            checkBatchSize(batchSize);
        }
        return -1;
    }

    @Override
    public int deallocate(TornadoDeviceObjectState state) {
        if (state.isLockedBuffer()) {
            return -1;
        }

        state.getObjectBuffer().deallocate();
        state.setContents(false);
        state.setObjectBuffer(null);
        return -1;
    }

    private ObjectBuffer createArrayWrapper(Class<?> type, PTXDeviceContext deviceContext, long batchSize) {
        ObjectBuffer result = null;
        if (type == int[].class) {
            result = new PTXIntArrayWrapper(deviceContext);
        } else if (type == short[].class) {
            result = new PTXShortArrayWrapper(deviceContext);
        } else if (type == byte[].class) {
            result = new PTXByteArrayWrapper(deviceContext);
        } else if (type == float[].class) {
            result = new PTXFloatArrayWrapper(deviceContext);
        } else if (type == double[].class) {
            result = new PTXDoubleArrayWrapper(deviceContext);
        } else if (type == long[].class) {
            result = new PTXLongArrayWrapper(deviceContext);
        } else if (type == char[].class) {
            result = new PTXCharArrayWrapper(deviceContext);
        } else {
            TornadoInternalError.unimplemented("array of type %s", type.getName());
        }
        return result;
    }

    private ObjectBuffer createMultiArrayWrapper(Class<?> componentType, Class<?> type, long batchSize) {
        ObjectBuffer result = null;
        PTXDeviceContext deviceContext = getDeviceContext();

        if (componentType == int[].class) {
            result = new PTXMultiDimArrayWrapper<>(deviceContext, PTXIntArrayWrapper::new, batchSize);
        } else if (componentType == short[].class) {
            result = new PTXMultiDimArrayWrapper<>(deviceContext, PTXShortArrayWrapper::new, batchSize);
        } else if (componentType == char[].class) {
            result = new PTXMultiDimArrayWrapper<>(deviceContext, PTXCharArrayWrapper::new, batchSize);
        } else if (componentType == byte[].class) {
            result = new PTXMultiDimArrayWrapper<>(deviceContext, PTXByteArrayWrapper::new, batchSize);
        } else if (componentType == float[].class) {
            result = new PTXMultiDimArrayWrapper<>(deviceContext, PTXFloatArrayWrapper::new, batchSize);
        } else if (componentType == double[].class) {
            result = new PTXMultiDimArrayWrapper<>(deviceContext, PTXDoubleArrayWrapper::new, batchSize);
        } else if (componentType == long[].class) {
            result = new PTXMultiDimArrayWrapper<>(deviceContext, PTXLongArrayWrapper::new, batchSize);
        } else {
            TornadoInternalError.unimplemented("array of type %s", type.getName());
        }
        return result;
    }

    /**
     * It allocates and copy in the content of the object to the target device.
     *
     * @param object
     *            to be allocated
     * @param objectState
     *            state of the object in the target device
     *            {@link TornadoDeviceObjectState}
     * @param events
     *            list of pending events (dependencies)
     * @param batchSize
     *            size of the object to be allocated. If this value is <= 0, then it
     *            allocates the sizeof(object).
     * @param hostOffset
     *            offset in bytes for the copy within the host input array (or
     *            object)
     * @return an event ID
     */
    @Override
    public List<Integer> ensurePresent(Object object, TornadoDeviceObjectState objectState, int[] events, long batchSize, long hostOffset) {
        if (!objectState.hasContents() || BENCHMARKING_MODE) {
            objectState.setContents(true);
            return objectState.getObjectBuffer().enqueueWrite(object, batchSize, hostOffset, events, events != null);
        }
        return null;
    }

    /**
     * It always copies in the input data (object) from the host to the target
     * device.
     *
     * @param object
     *            to be copied
     * @param batchSize
     *            size of the object to be allocated. If this value is <= 0, then it
     *            allocates the sizeof(object).
     * @param hostOffset
     *            offset in bytes for the copy within the host input array (or
     *            object)
     * @param objectState
     *            state of the object in the target device
     *            {@link TornadoDeviceObjectState}
     * @param events
     *            list of previous events
     * @return and event ID
     */
    @Override
    public List<Integer> streamIn(Object object, long batchSize, long hostOffset, TornadoDeviceObjectState objectState, int[] events) {
        objectState.setContents(true);
        return objectState.getObjectBuffer().enqueueWrite(object, batchSize, hostOffset, events, events != null);
    }

    /**
     * It copies a device buffer from the target device to the host. Copies are
     * non-blocking
     *
     * @param object
     *            to be copied.
     * @param hostOffset
     *            offset in bytes for the copy within the host input array (or
     *            object)
     * @param objectState
     *            state of the object in the target device
     *            {@link TornadoDeviceObjectState}
     * @param events
     *            of pending events
     * @return and event ID
     */
    @Override
    public int streamOut(Object object, long hostOffset, TornadoDeviceObjectState objectState, int[] events) {
        TornadoInternalError.guarantee(objectState.hasObjectBuffer(), "invalid variable");
        int event = objectState.getObjectBuffer().enqueueRead(object, hostOffset, events, events != null);
        if (events != null) {
            return event;
        }
        return -1;
    }

    /**
     * It copies a device buffer from the target device to the host. Copies are
     * blocking between the device and the host.
     *
     * @param object
     *            to be copied.
     * @param hostOffset
     *            offset in bytes for the copy within the host input array (or
     *            object)
     * @param objectState
     *            state of the object in the target device
     *            {@link TornadoDeviceObjectState}
     * @param events
     *            of pending events
     * @return and event ID
     */
    @Override
    public int streamOutBlocking(Object object, long hostOffset, TornadoDeviceObjectState objectState, int[] events) {
        TornadoInternalError.guarantee(objectState.hasObjectBuffer(), "invalid variable");
        return objectState.getObjectBuffer().read(object, hostOffset, events, events != null);
    }

    /**
     * It resolves an pending event.
     *
     * @param event
     *            ID
     * @return an object of type {@link Event}
     */
    @Override
    public Event resolveEvent(int event) {
        return getDeviceContext().resolveEvent(event);
    }

    @Override
    public void ensureLoaded() {
        getDeviceContext().flushEvents();
    }

    @Override
    public void flushEvents() {
        getDeviceContext().flushEvents();
    }

    @Override
    public int enqueueBarrier() {
        return getDeviceContext().enqueueBarrier();
    }

    @Override
    public int enqueueBarrier(int[] events) {
        return getDeviceContext().enqueueBarrier(events);
    }

    @Override
    public int enqueueMarker() {
        return getDeviceContext().enqueueMarker();
    }

    @Override
    public int enqueueMarker(int[] events) {
        return getDeviceContext().enqueueMarker(events);
    }

    @Override
    public void sync() {
        getDeviceContext().sync();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof PTXTornadoDevice) {
            final PTXTornadoDevice other = (PTXTornadoDevice) obj;
            return (other.deviceIndex == deviceIndex);
        }
        return false;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 89 * hash + this.deviceIndex;
        return hash;
    }

    @Override
    public void flush() {
        getDeviceContext().flush();
    }

    @Override
    public void reset() {
        device.getPTXContext().getDeviceContext().reset();
    }

    @Override
    public void dumpEvents() {
        getDeviceContext().dumpEvents();
    }

    @Override
    public String getDeviceName() {
        return "cuda-" + device.getDeviceIndex();
    }

    @Override
    public String getDescription() {
        return String.format("%s %s", device.getDeviceName(), device.getDeviceType());
    }

    @Override
    public String getPlatformName() {
        return PTX.getPlatform().getName();
    }

    @Override
    public PTXDeviceContext getDeviceContext() {
        return getBackend().getDeviceContext();
    }

    public PTXBackend getBackend() {
        return driver.getBackend(device.getDeviceIndex());
    }

    @Override
    public TornadoTargetDevice getPhysicalDevice() {
        return device;
    }

    @Override
    public TornadoMemoryProvider getMemoryProvider() {
        return getDeviceContext().getMemoryManager();
    }

    @Override
    public TornadoDeviceType getDeviceType() {
        return TornadoDeviceType.GPU;
    }

    @Override
    public long getMaxAllocMemory() {
        return device.getDeviceMaxAllocationSize();
    }

    @Override
    public long getMaxGlobalMemory() {
        return device.getDeviceGlobalMemorySize();
    }

    @Override
    public long getDeviceLocalMemorySize() {
        return device.getDeviceLocalMemorySize();
    }

    @Override
    public long[] getDeviceMaxWorkgroupDimensions() {
        return device.getDeviceMaxWorkItemSizes();
    }

    @Override
    public String getDeviceOpenCLCVersion() {
        return "N/A";
    }

    @Override
    public Object getDeviceInfo() {
        return device.getDeviceInfo();
    }

    @Override
    public int getDriverIndex() {
        return TornadoCoreRuntime.getTornadoRuntime().getDriverIndex(PTXDriver.class);
    }

    @Override
    public Object getAtomic() {
        return null;
    }

    @Override
    public void setAtomicsMapping(ConcurrentHashMap<Object, Integer> mappingAtomics) {

    }

    @Override
    public TornadoVMBackendType getTornadoVMBackend() {
        return TornadoVMBackendType.PTX;
    }

    @Override
    public boolean isSPIRVSupported() {
        return false;
    }

    /**
     * In CUDA the context is not attached to the whole process, but to individual
     * threads Therefore, in the case of new threads executing a task schedule, we
     * must make sure that the context is set for that thread.
     */
    @Override
    public void enableThreadSharing() {
        device.getPTXContext().enablePTXContext();
    }

    @Override
    public void setAtomicRegion(ObjectBuffer bufferAtomics) {

    }

    @Override
    public String toString() {
        return getPlatformName() + " -- " + device.getDeviceName();
    }

}
