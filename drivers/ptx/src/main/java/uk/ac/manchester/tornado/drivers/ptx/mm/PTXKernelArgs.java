/*
 * This file is part of Tornado: A heterogeneous programming framework:
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2022, APT Group, Department of Computer Science,
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
package uk.ac.manchester.tornado.drivers.ptx.mm;

import uk.ac.manchester.tornado.drivers.ptx.PTXDeviceContext;
import uk.ac.manchester.tornado.runtime.common.KernelArgs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


public class PTXKernelArgs extends PTXByteBuffer implements KernelArgs {

    public static final int RESERVED_SLOTS = 3;
    private final ArrayList<CallArgument> callArguments;

    public PTXKernelArgs(long address, int numArgs, PTXDeviceContext deviceContext) {
        super(address,RESERVED_SLOTS << 3, 0, deviceContext);
        this.callArguments = new ArrayList<>(numArgs);

        buffer.clear();
    }

    @Override
    public void addCallArgument(Object value, boolean isReferenceType) {
        callArguments.add(new CallArgument(value, isReferenceType));
    }

    @Override
    public void reset() {
        callArguments.clear();
    }

    @Override
    public List<CallArgument> getCallArguments() {
        return callArguments;
    }

    @Override
    public void write() {
        super.write();
    }

    @Override
    public int enqueueWrite() {
        return enqueueWrite(null);
    }

    @Override
    public int enqueueWrite(int[] events) {
        return super.enqueueWrite(events);
    }

    @Override
    public void setKernelContext(HashMap<Integer, Integer> map) {
        buffer.clear();
        for (int i = 0; i < RESERVED_SLOTS; i++) {
            if (map.containsKey(i)) {
                buffer.putLong(map.get(i));
            } else {
                buffer.putLong(0);
            }
        }
    }
}
