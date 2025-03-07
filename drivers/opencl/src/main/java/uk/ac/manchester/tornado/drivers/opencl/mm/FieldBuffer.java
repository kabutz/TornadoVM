/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2013-2020, APT Group, Department of Computer Science,
 * The University of Manchester. All rights reserved.
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
 * Authors: James Clarkson
 *
 */
package uk.ac.manchester.tornado.drivers.opencl.mm;

import static uk.ac.manchester.tornado.runtime.common.Tornado.DEBUG;
import static uk.ac.manchester.tornado.runtime.common.Tornado.debug;
import static uk.ac.manchester.tornado.runtime.common.Tornado.trace;
import static uk.ac.manchester.tornado.runtime.common.Tornado.warn;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

import uk.ac.manchester.tornado.api.exceptions.TornadoMemoryException;
import uk.ac.manchester.tornado.api.exceptions.TornadoOutOfMemoryException;
import uk.ac.manchester.tornado.api.mm.ObjectBuffer;
import uk.ac.manchester.tornado.runtime.common.RuntimeUtilities;

public class FieldBuffer {

    private final Field field;
    private final ObjectBuffer objectBuffer;

    public FieldBuffer(final Field field, final ObjectBuffer objectBuffer) {
        this.objectBuffer = objectBuffer;
        this.field = field;
    }

    public int enqueueRead(final Object ref, final int[] events, boolean useDeps) {
        if (DEBUG) {
            trace("fieldBuffer: enqueueRead* - field=%s, parent=0x%x, child=0x%x", field, ref.hashCode(), getFieldValue(ref).hashCode());
        }
        // TODO: Offset 0
        return (useDeps) ? objectBuffer.enqueueRead(getFieldValue(ref), 0, (useDeps) ? events : null, useDeps) : -1;
    }

    public List<Integer> enqueueWrite(final Object ref, final int[] events, boolean useDeps) {
        if (DEBUG) {
            trace("fieldBuffer: enqueueWrite* - field=%s, parent=0x%x, child=0x%x", field, ref.hashCode(), getFieldValue(ref).hashCode());
        }
        return (useDeps) ? objectBuffer.enqueueWrite(getFieldValue(ref), 0, 0, (useDeps) ? events : null, useDeps) : null;
    }

    private Object getFieldValue(final Object container) {
        Object value = null;
        try {
            value = field.get(container);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            warn("Illegal access to field: name=%s, object=0x%x", field.getName(), container.hashCode());
        }
        return value;
    }

    public void read(final Object ref) {
        read(ref, null, false);
    }

    public int read(final Object ref, int[] events, boolean useDeps) {
        if (DEBUG) {
            debug("fieldBuffer: read - field=%s, parent=0x%x, child=0x%x", field, ref.hashCode(), getFieldValue(ref).hashCode());
        }
        // TODO: reading with offset != 0
        return objectBuffer.read(getFieldValue(ref), 0, events, useDeps);
    }

    public void write(final Object ref) {
        if (DEBUG) {
            trace("fieldBuffer: write - field=%s, parent=0x%x, child=0x%x", field, ref.hashCode(), getFieldValue(ref).hashCode());
        }
        objectBuffer.write(getFieldValue(ref));
    }

    public String getFieldName() {
        return field.getName();
    }

    public long size() {
        return objectBuffer.size();
    }

    void setBuffer(ObjectBuffer.ObjectBufferWrapper bufferWrapper) {
        objectBuffer.setBuffer(bufferWrapper);
    }

    long getBufferOffset() {
        return objectBuffer.getBufferOffset();
    }

}
