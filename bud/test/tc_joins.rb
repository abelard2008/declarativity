require 'rubygems'
require 'bud'
require 'test/unit'

class CombosBud < Bud

  def state
    table :r, ['x', 'y1']
    table :s_tab, ['x', 'y1']
    table :t, ['x', 'y1']
    table :mismatches, ['x', 'y1']
    scratch :simple_out, ['x', 'y1', 'y2']
    scratch :match_out, ['x', 'y1', 'y2']
    scratch :chain_out, ['x1', 'x2', 'x3', 'y1', 'y2', 'y3']
    scratch :flip_out, ['x1', 'x2', 'x3', 'y1', 'y2', 'y3']
    scratch :nat_out, ['x1', 'x2', 'x3', 'y1', 'y2', 'y3']
    scratch :loj_out, ['x1', 'x2', 'y1', 'y2']
  end
  
  declare
  def program
    r << ['a', 1]
    r << ['b', 1]
    r << ['b', 2]
    r << ['c', 1]
    r << ['c', 2]
    s_tab << ['a', 1]
    s_tab << ['b', 2]
    s_tab << ['c', 1]
    s_tab << ['c', 2]
    t << ['a', 1]
    t << ['z', 1]
    mismatches << ['a', 1]
    mismatches << ['v', 1]
    mismatches << ['z', 1]    

    j = join [r,s_tab], [r.x, s_tab.x]
    simple_out <= j.map { |t1,t2| [t1.x, t1.y1, t2.y1] }
    
    k = join [r,s_tab], [r.x, s_tab.x], [r.y1, s_tab.y1]
    match_out <= k.map { |t1,t2| [t1.x, t1.y1, t2.y1] }
    
    l = join [r,s_tab,t], [r.x, s_tab.x], [s_tab.x, t.x]
    chain_out <= l.map { |t1, t2, t3| [t1.x, t2.x, t3.x, t1.y1, t2.y1, t3.y1] }
    
    m = join [r,s_tab,t], [r.x, s_tab.x, t.x]
    flip_out <= m.map { |t1, t2, t3| [t1.x, t2.x, t3.x, t1.y1, t2.y1, t3.y1] }
    
    n = natjoin [r,s_tab,t]
    nat_out <= n.map { |t1, t2, t3| [t1.x, t2.x, t3.x, t1.y1, t2.y1, t3.y1] }
    
    #loj = leftjoin [mismatches,s_tab], [mismatches.x, s_tab.x]
    #loj_out <= loj.map { |t1, t2| [t1.x, t2.x, t1.y1, t2.y1] }
  end
end

class TestJoins < Test::Unit::TestCase
  def test_combos
    program = CombosBud.new('localhost', 12345, {'visualize' => false})
    assert_nothing_raised( RuntimeError) { program.tick }
    simple_outs = program.simple_out.map {|t| t}
    assert_equal(7, simple_outs.length)
    assert_equal(1, simple_outs.select { |t| t[0] == 'a'} .length)
    assert_equal(2, simple_outs.select { |t| t[0] == 'b'} .length)
    assert_equal(4, simple_outs.select { |t| t[0] == 'c'} .length)
  end
  
  def test_secondary_join_predicates
    program = CombosBud.new('localhost', 12345)
    assert_nothing_raised( RuntimeError) { program.tick }
    match_outs = program.match_out.map {|t| t}
    assert_equal(4, match_outs.length)
    assert_equal(1, match_outs.select { |t| t[0] == 'a'} .length)
    assert_equal(1, match_outs.select { |t| t[0] == 'b'} .length)
    assert_equal(2, match_outs.select { |t| t[0] == 'c'} .length)
  end   
  
  def test_3_joins
    program = CombosBud.new('localhost', 12345)
    assert_nothing_raised( RuntimeError) { program.tick }
    chain_outs = program.chain_out.map {|t| t}
    assert_equal(1, chain_outs.length)
    flip_outs = program.flip_out.map {|t| t}
    assert_equal(1, flip_outs.length)
    nat_outs = program.nat_out.map{|t| t}
    assert_equal(1, nat_outs.length)
    assert_equal(chain_outs, flip_outs)
  end
  
  # PAA: problems recognizing 'leftjoin()' ? 
  # fix.
  def ntest_left_outer_join
    program = CombosBud.new('localhost', 12345)
    assert_nothing_raised( RuntimeError) { program.tick }
    loj_outs = program.loj_out.map{|t| t}
    assert_equal(3, loj_outs.length)
    assert_equal(loj_outs.inspect, '[["z", nil, 1, nil], ["v", nil, 1, nil], ["a", "a", 1, 1]]')
  end
end
