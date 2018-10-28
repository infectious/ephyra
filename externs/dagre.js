// Generated with http://www.dotnetwise.com/code/externs/
//
//
// Host the dagre.min.js file on localhost with a server allowing cross-origin requests
// and load that file to the tool.
//
// And then, when you compile ClojureScript with optimisations, it doesn't work anyway,
// so go and edit the file manually.
var dagre = {
    "graphlib": {
        "Graph": function () {},
        "json": {
            "write": function () {},
            "read": function () {}
        },
        "alg": {
            "components": function () {},
            "dijkstra": function () {},
            "dijkstraAll": function () {},
            "findCycles": function () {},
            "floydWarshall": function () {},
            "isAcyclic": function () {},
            "postorder": function () {},
            "preorder": function () {},
            "prim": function () {},
            "tarjan": function () {},
            "topsort": function () {}
        },
        "version": {}
    },
    "layout": function () {},
    "debug": {
        "debugOrdering": function () {}
    },
    "util": {
        "time": function () {},
        "notime": function () {}
    },
    "version": {}
}

dagre.graphlib.Graph.prototype = {
    "edge": function() {},
    "edges": function() {},
    "graph": function() {},
    "node": function() {},
    "nodes": function() {},
    "setDefaultEdgeLabel": function() {},
    "setEdge": function() {},
    "setGraph": function() {},
    "setNode": function() {},
    "sources": function() {}
};
