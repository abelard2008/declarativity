# simple grep
require 'rubygems'
require 'bud'
require 'test/unit'
require 'backports'

class WordCount2 < Bud
  attr_reader :pattern
  
  def initialize(ip, port, pattern)
    super(ip,port)
    @pattern = pattern
  end
  
  def state
    file_reader :txt, '../examples/chap2/ulysses.txt'
    scratch :words, ['lineno', 'wordno'], ['word']
    scratch :wc, ['word'], ['cnt']
  end
  
  declare 
  def program
    words <= txt.flat_map do |t|
      t.text.split.enum_for(:each_with_index).map {|w, i| [t.lineno, i, w]}
    end
    wc <= words.reduce({}) do |memo, t|
      memo[t.word] ||= 0
      memo[t.word] += 1
      memo
    end
  end
end

class TestWC2 < Test::Unit::TestCase
  def test_wc2
    program = WordCount2.new('localhost', ARGV[0], /[Bb]loom/)
    assert_nothing_raised { program.tick }
    assert_equal(23, program.wc[["yes"]].cnt)
  end
end
