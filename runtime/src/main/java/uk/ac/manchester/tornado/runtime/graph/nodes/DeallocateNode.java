/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2022, APT Group, Department of Computer Science,
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
 */
package uk.ac.manchester.tornado.runtime.graph.nodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DeallocateNode extends ContextOpNode {

    public DeallocateNode(ContextNode context) {
        super(context);
    }

    private ObjectNode value;
    private ContextOpNode dependent;

    public ContextOpNode getDependent() {
        return dependent;
    }

    public void setDependent(ContextOpNode dependent) {
        this.dependent = dependent;
    }

    public void setValue(ObjectNode object) {
        value = object;
    }

    public ObjectNode getValue() {
        return value;
    }

    public String toString() {
        return String.format("[%d]: deallocate object %d after %d", id, value.getId(), dependent.getId());
    }

    public boolean hasInputs() {
        return value != null;
    }

    public List<AbstractNode> getInputs() {
        if (!hasInputs()) {
            return Collections.emptyList();
        }

        final List<AbstractNode> result = new ArrayList<AbstractNode>();
        result.add(value);
        result.add(dependent);
        return result;
    }
}
