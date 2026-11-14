package graphql

import (
	"errors"
	"strconv"

	"github.com/graphql-go/graphql"
	"github.com/graphql-go/graphql/language/ast"
)

const MaxLiteralDepth = 5

var forbiddenKeys = map[string]bool{
	"__proto__":   true,
	"constructor": true,
	"prototype":   true,
}

func parseLiteralNode(node ast.Value, depth int) (any, error) {
	if depth > MaxLiteralDepth {
		return nil, errors.New("Input literal nesting exceeds maximum allowed depth.")
	}

	if node == nil {
		return nil, nil
	}

	switch v := node.(type) {
	case *ast.StringValue:
		return v.Value, nil
	case *ast.BooleanValue:
		return v.Value, nil
	case *ast.IntValue:
		if i, err := strconv.ParseInt(v.Value, 10, 64); err == nil {
			return int(i), nil
		}
		return v.Value, nil
	case *ast.FloatValue:
		if f, err := strconv.ParseFloat(v.Value, 64); err == nil {
			return f, nil
		}
		return v.Value, nil
	case *ast.EnumValue:
		return v.Value, nil
	case *ast.ListValue:
		list := make([]any, 0, len(v.Values))
		for _, item := range v.Values {
			parsed, err := parseLiteralNode(item, depth+1)
			if err != nil {
				return nil, err
			}
			list = append(list, parsed)
		}
		return list, nil
	case *ast.ObjectValue:
		obj := make(map[string]any, len(v.Fields))
		for _, f := range v.Fields {
			key := ""
			if f.Name != nil {
				key = f.Name.Value
			}
			if forbiddenKeys[key] {
				return nil, errors.New("Invalid input object key.")
			}
			val, err := parseLiteralNode(f.Value, depth+1)
			if err != nil {
				return nil, err
			}
			obj[key] = val
		}
		return obj, nil
	default:
		return nil, nil
	}
}

// ParseLiteral parses AST literal values with depth and prototype security checks.
func ParseLiteral(astNode ast.Value) any {
	val, err := parseLiteralNode(astNode, 0)
	if err != nil {
		// In graphql-go, returning nil from ParseLiteral indicates a parse error,
		// or panicking with error produces a GraphQL validation error.
		panic(err)
	}
	return val
}

// JSONScalar is a pass-through custom scalar allowing uncoerced inputs to reach domain validation.
var JSONScalar = graphql.NewScalar(graphql.ScalarConfig{
	Name:        "JSONScalar",
	Description: "Pass-through scalar for arbitrary JSON primitives or structures without coercion",
	Serialize: func(value any) any {
		return value
	},
	ParseValue: func(value any) any {
		return value
	},
	ParseLiteral: ParseLiteral,
})
