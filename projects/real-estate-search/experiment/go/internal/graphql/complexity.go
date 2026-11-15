package graphql

import (
	"errors"
	"fmt"

	"github.com/graphql-go/graphql/language/ast"
	"github.com/graphql-go/graphql/language/parser"
	"github.com/graphql-go/graphql/language/source"
)

const (
	MaxRootFields     = 10
	MaxQueryDepth     = 15
	maxTraversalSteps = 1000
)

func countRootFields(
	selectionSet *ast.SelectionSet,
	fragments map[string]*ast.FragmentDefinition,
	visiting map[string]bool,
	steps *int,
) (int, error) {
	if selectionSet == nil {
		return 0, nil
	}

	*steps++
	if *steps > maxTraversalSteps {
		return 0, fmt.Errorf("Query exceeds maximum allowed root field count (%d).", MaxRootFields)
	}

	total := 0
	for _, selection := range selectionSet.Selections {
		switch s := selection.(type) {
		case *ast.Field:
			total++
			if total > MaxRootFields {
				return total, fmt.Errorf("Query exceeds maximum allowed root field count (%d).", MaxRootFields)
			}
		case *ast.InlineFragment:
			if s.SelectionSet != nil {
				n, err := countRootFields(s.SelectionSet, fragments, visiting, steps)
				if err != nil {
					return 0, err
				}
				total += n
				if total > MaxRootFields {
					return total, fmt.Errorf("Query exceeds maximum allowed root field count (%d).", MaxRootFields)
				}
			}
		case *ast.FragmentSpread:
			if s.Name == nil {
				continue
			}
			fragName := s.Name.Value
			if visiting[fragName] {
				return 0, errors.New("Fragment cycle detected.")
			}
			frag, ok := fragments[fragName]
			if !ok || frag.SelectionSet == nil {
				continue
			}
			visiting[fragName] = true
			n, err := countRootFields(frag.SelectionSet, fragments, visiting, steps)
			delete(visiting, fragName)
			if err != nil {
				return 0, err
			}
			total += n
			if total > MaxRootFields {
				return total, fmt.Errorf("Query exceeds maximum allowed root field count (%d).", MaxRootFields)
			}
		}
	}
	return total, nil
}

func measureSelectionDepth(
	selectionSet *ast.SelectionSet,
	depth int,
	fragments map[string]*ast.FragmentDefinition,
	visiting map[string]bool,
	steps *int,
) (int, error) {
	if depth > MaxQueryDepth {
		return depth, fmt.Errorf("Query exceeds maximum allowed depth (%d).", MaxQueryDepth)
	}
	if selectionSet == nil {
		return depth, nil
	}

	*steps++
	if *steps > maxTraversalSteps {
		return 0, fmt.Errorf("Query exceeds maximum allowed depth (%d).", MaxQueryDepth)
	}

	deepest := depth
	for _, selection := range selectionSet.Selections {
		switch s := selection.(type) {
		case *ast.Field:
			if s.SelectionSet != nil {
				d, err := measureSelectionDepth(s.SelectionSet, depth+1, fragments, visiting, steps)
				if err != nil {
					return 0, err
				}
				if d > deepest {
					deepest = d
				}
			}
		case *ast.InlineFragment:
			if s.SelectionSet != nil {
				d, err := measureSelectionDepth(s.SelectionSet, depth+1, fragments, visiting, steps)
				if err != nil {
					return 0, err
				}
				if d > deepest {
					deepest = d
				}
			}
		case *ast.FragmentSpread:
			if s.Name == nil {
				continue
			}
			fragName := s.Name.Value
			if visiting[fragName] {
				return 0, errors.New("Fragment cycle detected.")
			}
			frag, ok := fragments[fragName]
			if !ok || frag.SelectionSet == nil {
				continue
			}
			visiting[fragName] = true
			d, err := measureSelectionDepth(frag.SelectionSet, depth+1, fragments, visiting, steps)
			delete(visiting, fragName)
			if err != nil {
				return 0, err
			}
			if d > deepest {
				deepest = d
			}
		}
	}
	return deepest, nil
}

func unwrapNamedTypeName(t ast.Type) string {
	for {
		switch v := t.(type) {
		case *ast.NonNull:
			t = v.Type
		case *ast.List:
			t = v.Type
		case *ast.Named:
			if v.Name != nil {
				return v.Name.Value
			}
			return ""
		default:
			return ""
		}
	}
}

func validateStrictStringLiteral(val ast.Value, fieldName string, stringVars map[string]string) error {
	switch v := val.(type) {
	case nil, *ast.StringValue:
		return nil
	case *ast.Variable:
		if v.Name != nil {
			stringVars[v.Name.Value] = fieldName
		}
		return nil
	default:
		return fmt.Errorf("%s must be a string.", fieldName)
	}
}

func validateSearchInputObjectLiteral(obj *ast.ObjectValue, stringVars map[string]string) error {
	for _, f := range obj.Fields {
		key := ""
		if f.Name != nil {
			key = f.Name.Value
		}
		if forbiddenKeys[key] {
			return errors.New("Invalid input object key.")
		}
		if key == "city" || key == "keyword" {
			if err := validateStrictStringLiteral(f.Value, key, stringVars); err != nil {
				return err
			}
		}
		if _, err := ParseLiteralChecked(f.Value); err != nil {
			return err
		}
	}
	return nil
}

func validateDirectives(directives []*ast.Directive) error {
	for _, dir := range directives {
		if dir == nil {
			continue
		}
		for _, arg := range dir.Arguments {
			if arg == nil {
				continue
			}
			if _, err := ParseLiteralChecked(arg.Value); err != nil {
				return err
			}
		}
	}
	return nil
}

func validateSelectionLiterals(
	selectionSet *ast.SelectionSet,
	inputVars map[string]bool,
	stringVars map[string]string,
) error {
	if selectionSet == nil {
		return nil
	}

	for _, selection := range selectionSet.Selections {
		switch s := selection.(type) {
		case *ast.Field:
			if err := validateDirectives(s.Directives); err != nil {
				return err
			}
			fieldName := ""
			if s.Name != nil {
				fieldName = s.Name.Value
			}
			for _, arg := range s.Arguments {
				if arg == nil {
					continue
				}
				argName := ""
				if arg.Name != nil {
					argName = arg.Name.Value
				}
				if fieldName == "searchListings" && argName == "input" {
					switch val := arg.Value.(type) {
					case nil:
						continue
					case *ast.ObjectValue:
						if err := validateSearchInputObjectLiteral(val, stringVars); err != nil {
							return err
						}
						continue
					case *ast.Variable:
						if val.Name != nil {
							inputVars[val.Name.Value] = true
						}
						continue
					default:
						return errors.New("Search input must be an object.")
					}
				}
				if _, err := ParseLiteralChecked(arg.Value); err != nil {
					return err
				}
			}
			if err := validateSelectionLiterals(s.SelectionSet, inputVars, stringVars); err != nil {
				return err
			}
		case *ast.InlineFragment:
			if err := validateDirectives(s.Directives); err != nil {
				return err
			}
			if err := validateSelectionLiterals(s.SelectionSet, inputVars, stringVars); err != nil {
				return err
			}
		case *ast.FragmentSpread:
			if err := validateDirectives(s.Directives); err != nil {
				return err
			}
		}
	}
	return nil
}

func validateVariableValue(val any, depth int) error {
	if depth > MaxLiteralDepth {
		return errors.New("Input literal nesting exceeds maximum allowed depth.")
	}
	if val == nil {
		return nil
	}
	switch v := val.(type) {
	case map[string]any:
		for k, item := range v {
			if forbiddenKeys[k] {
				return errors.New("Invalid input object key.")
			}
			if err := validateVariableValue(item, depth+1); err != nil {
				return err
			}
		}
	case []any:
		for _, item := range v {
			if err := validateVariableValue(item, depth+1); err != nil {
				return err
			}
		}
	}
	return nil
}

func validateVariables(
	variables map[string]any,
	inputVars map[string]bool,
	stringVars map[string]string,
) error {
	for k, v := range variables {
		if forbiddenKeys[k] {
			return errors.New("Invalid input object key.")
		}
		if inputVars[k] {
			if v != nil {
				obj, ok := v.(map[string]any)
				if !ok {
					return errors.New("Search input must be an object.")
				}
				for fk, fv := range obj {
					if forbiddenKeys[fk] {
						return errors.New("Invalid input object key.")
					}
					if (fk == "city" || fk == "keyword") && fv != nil {
						if _, isStr := fv.(string); !isStr {
							return fmt.Errorf("%s must be a string.", fk)
						}
					}
					if err := validateVariableValue(fv, 0); err != nil {
						return err
					}
				}
			}
			continue
		}
		if fieldName, isStrVar := stringVars[k]; isStrVar && v != nil {
			if _, isStr := v.(string); !isStr {
				return fmt.Errorf("%s must be a string.", fieldName)
			}
		}
		if err := validateVariableValue(v, 0); err != nil {
			return err
		}
	}
	return nil
}

// ValidateGraphQLRequest validates query complexity, fragment depth/cycles, literal security, and strict input types.
func ValidateGraphQLRequest(queryString string, variables map[string]any) error {
	src := source.NewSource(&source.Source{
		Body: []byte(queryString),
		Name: "GraphQL request",
	})

	doc, err := parser.Parse(parser.ParseParams{Source: src})
	if err != nil {
		return err
	}

	fragments := make(map[string]*ast.FragmentDefinition)
	for _, def := range doc.Definitions {
		if frag, ok := def.(*ast.FragmentDefinition); ok && frag.Name != nil {
			fragments[frag.Name.Value] = frag
		}
	}

	inputVars := map[string]bool{"input": true}
	stringVars := make(map[string]string)

	for _, def := range doc.Definitions {
		switch d := def.(type) {
		case *ast.OperationDefinition:
			if err := validateDirectives(d.Directives); err != nil {
				return err
			}
			for _, vd := range d.VariableDefinitions {
				if vd == nil {
					continue
				}
				typeName := unwrapNamedTypeName(vd.Type)
				if typeName == "SearchListingsInput" && vd.Variable != nil && vd.Variable.Name != nil {
					inputVars[vd.Variable.Name.Value] = true
				}
				if vd.DefaultValue != nil {
					if typeName == "SearchListingsInput" {
						if obj, ok := vd.DefaultValue.(*ast.ObjectValue); ok {
							if err := validateSearchInputObjectLiteral(obj, stringVars); err != nil {
								return err
							}
							continue
						}
					}
					if _, err := ParseLiteralChecked(vd.DefaultValue); err != nil {
						return err
					}
				}
			}

			if d.SelectionSet == nil {
				continue
			}

			rootSteps := 0
			rootCount, err := countRootFields(d.SelectionSet, fragments, make(map[string]bool), &rootSteps)
			if err != nil {
				return err
			}
			if rootCount > MaxRootFields {
				return fmt.Errorf("Query exceeds maximum allowed root field count (%d).", MaxRootFields)
			}

			depthSteps := 0
			depth, err := measureSelectionDepth(d.SelectionSet, 1, fragments, make(map[string]bool), &depthSteps)
			if err != nil {
				return err
			}
			if depth > MaxQueryDepth {
				return fmt.Errorf("Query exceeds maximum allowed depth (%d).", MaxQueryDepth)
			}

			if err := validateSelectionLiterals(d.SelectionSet, inputVars, stringVars); err != nil {
				return err
			}

		case *ast.FragmentDefinition:
			if err := validateDirectives(d.Directives); err != nil {
				return err
			}
			if err := validateSelectionLiterals(d.SelectionSet, inputVars, stringVars); err != nil {
				return err
			}
		}
	}

	if err := validateVariables(variables, inputVars, stringVars); err != nil {
		return err
	}

	return nil
}

// CheckQueryComplexity parses and validates root field count, selection depth, and literal security.
func CheckQueryComplexity(queryString string) error {
	return ValidateGraphQLRequest(queryString, nil)
}
