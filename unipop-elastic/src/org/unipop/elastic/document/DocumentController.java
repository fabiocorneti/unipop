package org.unipop.elastic.document;

import io.searchbox.core.*;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.util.iterator.EmptyIterator;
import org.apache.tinkerpop.shaded.jackson.databind.JsonNode;
import org.apache.tinkerpop.shaded.jackson.databind.ObjectMapper;
import org.elasticsearch.index.engine.DocumentAlreadyExistsException;
import org.unipop.common.util.SchemaSet;
import org.unipop.elastic.common.ElasticClient;
import org.unipop.elastic.document.schema.DocEdgeSchema;
import org.unipop.elastic.document.schema.DocSchema;
import org.unipop.elastic.document.schema.DocVertexSchema;
import org.unipop.query.controller.SimpleController;
import org.unipop.query.mutation.AddEdgeQuery;
import org.unipop.query.mutation.AddVertexQuery;
import org.unipop.query.mutation.PropertyQuery;
import org.unipop.query.mutation.RemoveQuery;
import org.unipop.query.search.DeferredVertexQuery;
import org.unipop.query.search.SearchQuery;
import org.unipop.query.search.SearchVertexQuery;
import org.unipop.schema.reference.DeferredVertex;
import org.unipop.structure.UniEdge;
import org.unipop.structure.UniElement;
import org.unipop.structure.UniGraph;
import org.unipop.structure.UniVertex;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DocumentController implements SimpleController {

    private ElasticClient client;
    private Set<? extends DocVertexSchema> vertexSchemas;
    private Set<? extends DocEdgeSchema> edgeSchemas;
    private UniGraph graph;
    private ObjectMapper mapper = new ObjectMapper();

    public DocumentController(ElasticClient client, SchemaSet schemas, UniGraph graph) {
        this.client = client;
        this.vertexSchemas = schemas.get(DocVertexSchema.class, true);
        this.edgeSchemas = schemas.get(DocEdgeSchema.class, true);
        this.graph = graph;

        Iterator<String> indices = schemas.get(DocSchema.class, true).stream().map(DocSchema::getIndex).distinct().iterator();
        client.validateIndex(indices);
    }

    //region QueryController
    @Override
    public <E extends Element>  Iterator<E> search(SearchQuery<E> uniQuery) {
        Set<? extends DocSchema<E>> schemas = getSchemas(uniQuery.getReturnType());
        Function<DocSchema<E>, Search> toPredicatesFunction = (schema) ->
                schema.toPredicates(uniQuery);
        return search(schemas, toPredicatesFunction);
    }

    @Override
    public Iterator<Edge> search(SearchVertexQuery uniQuery) {
        Function<DocEdgeSchema, Search> toPredicatesFunction = (schema) ->
                schema.toPredicates(uniQuery);
        return search(edgeSchemas, toPredicatesFunction);
    }

    @Override
    public void fetchProperties(DeferredVertexQuery uniQuery) {
        Function<DocVertexSchema, Search> toPredicatesFunction = (schema) ->
                schema.toPredicates(uniQuery);
        Iterator<Vertex> search = search(vertexSchemas, toPredicatesFunction);

        Map<Object, DeferredVertex> vertexMap = uniQuery.getVertices().stream()
                .collect(Collectors.toMap(UniElement::id, Function.identity(), (a, b) -> a));
        search.forEachRemaining(newVertex -> {
            DeferredVertex deferredVertex = vertexMap.get(newVertex.id());
            if(deferredVertex != null) deferredVertex.loadProperties(newVertex);
        });
    }

    @Override
    public Edge addEdge(AddEdgeQuery uniQuery) {
        UniEdge edge = new UniEdge(uniQuery.getProperties(), uniQuery.getOutVertex(), uniQuery.getInVertex(), graph);
        try {
            index(this.edgeSchemas, edge);
        }
        catch(DocumentAlreadyExistsException ex) {
            throw Graph.Exceptions.edgeWithIdAlreadyExists(edge.id());
        }
        return edge;
    }

    @Override
    public Vertex addVertex(AddVertexQuery uniQuery) {
        UniVertex vertex = new UniVertex(uniQuery.getProperties(), graph);
        try {
            index(this.vertexSchemas, vertex);
        }
        catch(DocumentAlreadyExistsException ex){
            throw Graph.Exceptions.vertexWithIdAlreadyExists(vertex.id());
        }
        return vertex;
    }

    @Override
    public <E extends Element> void property(PropertyQuery<E> uniQuery) {
        Set<? extends DocSchema<E>> schemas = getSchemas(uniQuery.getElement().getClass());
        index(schemas, uniQuery.getElement());
    }

    @Override
    public <E extends Element> void remove(RemoveQuery<E> uniQuery) {
        uniQuery.getElements().forEach(element -> {
            Set<? extends DocSchema<Element>> schemas = getSchemas(element.getClass());
            delete(schemas, element);
        });
    }
    //endregion

    //region Helpers
    private <E extends Element> Set<? extends DocSchema<E>> getSchemas(Class elementClass) {
        if(Vertex.class.isAssignableFrom(elementClass))
            return (Set<? extends DocSchema<E>>) vertexSchemas;
        else return (Set<? extends DocSchema<E>>) edgeSchemas;
    }
    //endregion

    //region Elastic Queries

    private <E extends Element, S extends DocSchema<E>> Iterator<E> search(Set<? extends S> allSchemas,
                                                                           Function<S, Search> toSearchFunction) {
        Map<S, Search> schemas = new HashMap<>();
        for(S schema : allSchemas){
            Search search = toSearchFunction.apply(schema);
            if(search == null) continue;
            schemas.put(schema, search);
        }
        if(schemas.size() == 0) return EmptyIterator.instance();

        client.refresh();
        MultiSearch.Builder multiSearch = new MultiSearch.Builder(schemas.values());
        MultiSearchResult results = client.execute(multiSearch.build());
        if(!results.isSucceeded()) return EmptyIterator.instance();

        Iterator<S> schemaIterator = schemas.keySet().iterator();
        return results.getResponses().stream().flatMap(result ->
                parseResults(schemaIterator.next(), result.searchResult.getJsonString()).stream()).iterator();
    }

    private <E extends Element, S extends DocSchema<E>> List<E> parseResults(S schemas, String result) {
        List<E> results = new ArrayList<>();
        try {
            JsonNode hits = mapper.readTree(result).get("hits").get("hits");
            for (JsonNode hit : hits) {
                Map<String, Object> source = mapper.readValue(hit.get("_source").toString(), Map.class);
                DocSchema.Document document = new DocSchema.Document(hit.get("_index").asText(), hit.get("_type").asText(), hit.get("_id").asText(), source);
                E element = schemas.fromDocument(document);
                if(element != null) results.add(element);
            }
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        return results;
    }

    private <E extends Element> void index(Set<? extends DocSchema<E>> schemas, E element) {
        for(DocSchema<E> schema : schemas) {
            DocSchema.Document document = schema.toDocument(element);
            if (document!= null) {
                Index index = new Index.Builder(document.getFields()).index(document.getIndex()).type(document.getType()).id(document.getId()).build();
                client.bulk(index);
            }
        }
    }

    private <E extends Element> void delete(Set<? extends DocSchema<E>> schemas, E element) {
        for(DocSchema<E> schema : schemas) {
            DocSchema.Document document = schema.toDocument(element);
            if (document != null) {
                Delete build = new Delete.Builder(document.getId()).index(document.getIndex()).type(document.getType()).build();
                client.bulk(build);
            }
        }
    }

    //endregion
}
