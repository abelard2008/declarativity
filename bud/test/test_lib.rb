require 'rubygems'
require 'bud'
require 'test/unit'

class TestLib < Test::Unit::TestCase
  def advance(p)
    advancer(p.ip, p.port)
  end

  def advancer(ip, port)
    sleep 1
    send_channel(ip, port, "tickler", ["#{ip}:#{port}"])
  end

  def send_channel(ip, port, chan, payload)
    EventMachine::connect(ip, port) do |c|
      pl = ([chan, payload]).to_msgpack
      c.send_data(pl)
    end
  end

  def soft_tick(v)
    assert_nothing_raised(RuntimeError) {advancer(v.ip, v.port)}
    sleep 1
  end


  def add_members(b)
    b.member << ['ip-10-203-91-184.ec2.internal:12345']
    b.member << ['ip-10-202-70-4.ec2.internal:12346']
    b.member << ['localhost:12347']

  end
  

  def test_empty

  end
end
