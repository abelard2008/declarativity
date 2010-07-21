# pingpong demo #2
# This script unifies pinger and ponger into a single program.
# To run:
#  fire up one copy with 'ruby pingpong.rb 12345 2 true'
#  fire up a second copy with 'ruby pingpong.rb 12346 2'
#  you should see packets received on either side
require 'rubygems'
require 'bloom'

class PingPong < Bloom
  attr_reader :myloc
  attr_reader :otherloc

  def initialize(ip, port)
    super ip, port
    @myloc = ip.to_s + ":" + port.to_s
    loc1 = "127.0.0.1:12345"
    loc2 = "127.0.0.1:12346"
    @otherloc = (myloc == loc1) ? loc2 : loc1
  end

  def state
    channel :pingpongs, 0, ['otherloc', 'myloc', 'msg', 'wall', 'bloom']
    table   :pingbuf, ['otherloc', 'myloc', 'msg', 'wall', 'bloom']
    periodic :timer, ARGV[1]
  end

  def declaration
    strata[0] = rules {
      # if 3rd arg is true, at time tick 1 set up pingbuf with one tuple
      if ARGV[2] and bloomtime == 1 then
        puts "injecting into pingbuf"
        pingbuf << [@otherloc, @myloc, 'pong!', Time.new.to_s, bloomtime] 
        # puts "#{pingbuf.length} tuples in pingbuf"
        # puts "#{timer.length} tuples in timer"
      end
      # whenever we get a pingpong, store it in pingbuf
      pingbuf <= pingpongs

      # whenever we get a timer, send out the contents of pingbuf, and delete them for the next tick
      j = join [timer, pingbuf]
      pingpongs <+ j.map {|t,p| [@otherloc, @myloc, (p.msg == 'ping!') ? 'pong!' : 'ping!', t.time, bloomtime]}      
      pingbuf <- j.map {|t,p| [p.otherloc, p.myloc, p.msg, p.wall, p.bloom]}
    }
  end
end

program = PingPong.new('127.0.0.1', ARGV[0])
program.run
