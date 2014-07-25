package main

import (
	"fmt"
	"reflect"
)

type Foo struct{}

func (f Foo) Bar_1(x interface{}) interface{} {
	fmt.Printf("%d\n", x)
	return "Bar_1"
}

func (f Foo) Bar_2(x interface{}, y interface{}) interface{} {
	fmt.Printf("%d %d\n", x, y)
	return "Bar_2"
}

func (f Foo) Bar_2_VA(x, y interface{}, xs ...interface{}) interface{} {
	fmt.Printf("%d %d %d\n", x, y, xs)
	return "Bar_2_VA"
}

func (f Foo) Bar_ApplyTo(xs []interface{}) interface{} {
	var l = len(xs)
	switch {
	case l > 2:
		var v = reflect.ValueOf(f)
		var vs = make([]reflect.Value, len(xs))
		for i, x := range xs {
			vs[i] = reflect.ValueOf(x)
		}
		return v.MethodByName("Bar_2_VA").Call(vs)[0].Interface()
	case l == 1:
		return f.Bar_1(xs[0])
	case l == 2:
		return f.Bar_2(xs[0], xs[1])
	}
	panic(fmt.Sprintf("Invalid arity: %d", l))
}

func (f Foo) Bar(xs ...interface{}) interface{} {
	var l = len(xs)
	switch {
	case l > 2:
		return f.Bar_ApplyTo(xs)
	case l == 1:
		return f.Bar_1(xs[0])
	case l == 2:
		return f.Bar_2(xs[0], xs[1])
	}
	panic(fmt.Sprintf("Invalid arity: %d", l))
}

func double(x interface{}) float64 {
	switch x.(type) {
	case int:
		return float64(x.(int))
	case int64:
		return float64(x.(int64))
	default:
		return x.(float64)
	}
}

func long(x interface{}) int64 {
	switch x.(type) {
	case int:
		return int64(x.(int))
	case float64:
		return int64(x.(float64))
	default:
		return x.(int64)
	}
}

func plus_one(x interface{}) interface{} {
	return (double(x) + (1))
}

func main() {
	fmt.Printf("ClojureScript to Go [go] %v\n", plus_one(1))

	var foo = Foo{}
	fmt.Printf("%v\n", foo.Bar(8))

	var xs = []int{4, 5, 6, 7}
	var is = make([]interface{}, len(xs))
	for i, x := range xs {
		is[i] = x
	}
	fmt.Printf("%v\n", foo.Bar_ApplyTo(is))
	xs = []int{4}
	is = make([]interface{}, len(xs))
	for i, x := range xs {
		is[i] = x
	}
	fmt.Printf("%v\n", foo.Bar_ApplyTo(is))

	xs = []int{8, 9}
	is = make([]interface{}, len(xs))
	for i, x := range xs {
		is[i] = x
	}
	fmt.Printf("%v\n", foo.Bar_ApplyTo(is))

	fmt.Printf("%v\n", foo.Bar_1(2))
	fmt.Printf("%v\n", foo.Bar_2(2, 3))
	fmt.Printf("%v\n", foo.Bar_2_VA(2, 3, 4))

	fmt.Printf("%v\n", foo.Bar(2))
	fmt.Printf("%v\n", foo.Bar(2, 3))
	fmt.Printf("%v\n", foo.Bar(2, 3, 4))
	fmt.Printf("%v\n", foo.Bar())
}
