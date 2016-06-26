package org.unipop.process.edge;

import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.util.Attachable;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.unipop.process.bulk.UniBulkStep;
import org.unipop.process.properties.PropertyFetcher;
import org.unipop.query.StepDescriptor;
import org.unipop.query.controller.ControllerManager;
import org.unipop.query.search.DeferredVertexQuery;
import org.unipop.schema.reference.DeferredVertex;
import org.unipop.structure.UniGraph;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by sbarzilay on 6/8/16.
 */
public class UniGraphEdgeOtherVertexStep extends UniBulkStep<Edge, Vertex> implements PropertyFetcher {
    private Set<String> propertyKeys;
    private List<DeferredVertexQuery.DeferredVertexController> deferredVertexControllers;

    public UniGraphEdgeOtherVertexStep(Traversal.Admin traversal, UniGraph graph, ControllerManager controllerManager) {
        super(traversal, graph);
        this.propertyKeys = null;
        this.deferredVertexControllers = controllerManager.getControllers(DeferredVertexQuery.DeferredVertexController.class);
    }

    @Override
    public void addPropertyKey(String key) {
        if (getPropertyKeys() == null)
            propertyKeys = new HashSet<>();
        this.getPropertyKeys().add(key);
    }

    @Override
    public Set<String> getPropertyKeys() {
        return propertyKeys;
    }

    @Override
    public void fetchAllKeys() {
        this.propertyKeys = null;
    }

    @Override
    protected Iterator<Traverser.Admin<Vertex>> process(List<Traverser.Admin<Edge>> traversers) {
        List<Traverser.Admin<Vertex>> vertices = new ArrayList<>();
        traversers.forEach(traverser -> {
            final List<Object> objects = traverser.path().objects();
            if (objects.get(objects.size()-2) instanceof Vertex) {
                Vertex vertex = ElementHelper.areEqual((Vertex) objects.get(objects.size()-2), traverser.get().outVertex()) ?
                        traverser.get().inVertex() :
                        traverser.get().outVertex();
                vertices.add(traverser.split(vertex, this));
            }
        });

        if (propertyKeys == null || propertyKeys.size() > 0){
            List<DeferredVertex> v = vertices.stream().map(Attachable::get)
                    .filter(vertex -> vertex instanceof DeferredVertex)
                    .map(vertex -> ((DeferredVertex) vertex))
                    .filter(DeferredVertex::isDeferred)
                    .collect(Collectors.toList());
            DeferredVertexQuery query = new DeferredVertexQuery(v, propertyKeys, new StepDescriptor(this));
            deferredVertexControllers.forEach(deferredVertexController -> deferredVertexController.fetchProperties(query));
        }

        return vertices.iterator();
    }

    @Override
    public Set<TraverserRequirement> getRequirements() {
        return Collections.singleton(TraverserRequirement.PATH);
    }
}
