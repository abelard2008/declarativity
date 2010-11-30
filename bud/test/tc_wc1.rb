# simple grep
require 'rubygems'
require 'bud'
require 'test/unit'
require 'backports'

class WordCount1 < Bud
  attr_reader :pattern
  
  def initialize(ip, port, pattern)
    super(ip,port)
    @pattern = pattern
  end
  
  def state
    file_reader :txt, '../examples/chap2/ulysses.txt'
    # file_reader :txt, 'shaks12.txt'
    scratch :wc, ['word'], ['cnt']
  end
  
  declare 
  def program
    wc <= txt.flat_map do |t|
            t.text.split.enum_for(:each_with_index).map {|w, i| [t.lineno, i, w]}
          end.rename(['lineno','wordno','word']).group([:word], count)
  end
end

class TestWC1 < Test::Unit::TestCase
  def test_wc1
    program = WordCount1.new('localhost', ARGV[0], /[Bb]loom/)
    assert_nothing_raised { program.tick }
    assert_equal(23, program.wc[["yes"]].cnt)
  end
end