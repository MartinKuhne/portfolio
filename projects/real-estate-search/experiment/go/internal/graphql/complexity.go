package graphql

import (
	"fmt"

	"github.com/graphql-go/graphql/language/ast"
	"github.com/graphql-go/graphql/language/parser"
	"github.com/graphql-go/graphql/language/source"
)

const (
	MaxRootFields = 10
	MaxQueryDepth = 15
)

func measureSelectionDepth(selectionSet *ast.SelectionSet, depth int) int {
	if selectionSet == nil {
		return depth
	}

	deepest := depth
	for _, selection := range selectionSet.Selections {
		switch s := selection.(type) {
		case *ast.Field:
			if s.SelectionSet != nil {
				d := measureSelectionDepth(s.SelectionSet, depth+1)
				if d > deepest {
					deepest = d
				}
			}
		case *ast.InlineFragment:
			if s.SelectionSet != nil {
				d := measureSelectionDepth(s.SelectionSet, depth+1)
				if d > deepest {
					deepest = d
				}
			}
		}
	}
	return deepest
}

// CheckQueryComplexity parses and validates root field count and selection nesting depth.
func CheckQueryComplexity(queryString string) error {
	src := source.NewSource(&source.Source{
		Body: []byte(queryString),
		Name: "GraphQL request",
	})

	doc, err := parser.Parse(parser.ParseParams{Source: src})
	if err != nil {
		return err
	}

	for _, def := range doc.Definitions {
		op, ok := def.(*ast.OperationDefinition)
		if !ok || op.SelectionSet == nil {
			continue
		}

		if len(op.SelectionSet.Selections) > MaxRootFields {
			return fmt.Errorf("Query exceeds maximum allowed root field count (%d).", MaxRootFields)
		}

		if measureSelectionDepth(op.SelectionSet, 1) > MaxQueryDepth {
			return fmt.Errorf("Query exceeds maximum allowed depth (%d).", MaxQueryDepth)
		}
	}

	return nil
}
