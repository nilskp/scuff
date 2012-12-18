package scuff.js

import org.junit._
import org.junit.Assert._

import CoffeeScriptCompiler._

class TestCoffeeScriptCompiler {
  @Test
  def simple2() {
    val compiler = CoffeeScriptCompiler(true, false)
    val coffee = """
arr = [3,5,23,67,34]
[foo, bar] = arr
sqr = (a) -> a*2
boo = sqr(15)
"""
    val js = compiler.compile(coffee)
    println(js)
  }

  @Test
  def other() {
    val coffee = """
arr = [3,5,23,67,34]
[foo, bar] = arr
#sqr = (a) -> a*3
boo = sqr 15
"""
    val compiler = CoffeeScriptCompiler(false, false, 'bare -> true)
    val js = compiler.compile(coffee)
    println(js)
  }
}