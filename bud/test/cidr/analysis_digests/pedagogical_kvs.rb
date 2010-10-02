require 'rubygems'
require 'bud'
require 'cidr/analysis_digests/reliable_delivery'

#class BudKVS < BestEffortDelivery
class BudKVS < ReliableDelivery
  def state
    super
    table :bigtable, ['key'], ['value']
    table :stor_saved, ['server','client', 'key', 'reqid', 'value']
    table :member, ['peer']
    scratch :kvput, ['server', 'client', 'key', 'reqid'], ['value']
  end

  declare
    def kstore
      #readback = join [kvput, pipe_out], [kvput.reqid, pipe_out.id]
      #stor_saved <- readback.map{ |s, p| s }
      #stor_saved <+ kvput.map do |k| 
      #  #print "put on kvs: #{k.inspect}\n"
      #  k
      #end
      bigtable <+ join([kvput, pipe_out], [kvput.reqid, pipe_out.id]).map do |s, p| 
        #print "->BT: #{s.key} == #{s.value}\n"
        [s.key, s.value] 
      end

      #jst = join [bigtable, kvput, pipe_out], [bigtable.key, kvput.key], [kvput.reqid, pipe_out.id]
      bigtable <- join([bigtable, kvput, pipe_out], [bigtable.key, kvput.key], [kvput.reqid, pipe_out.id]).map { |b, s, p| b }
    end

  declare
    def replicate
      jrep = join [kvput, member]
      pipe <+ jrep.map do |s, m|
        if m.peer != @addy and m.peer != s.client
          [m.peer, @addy, s.reqid, [s.key, s.value]]
        end
      end
  
      kvput <+ pipe_chan.map do |p|
        #print "raw off wire: #{p.inspect}\n"
        if @addy == p.dst and p.dst != p.src
          #print "in off wire: #{p.inspect}\n"
          # FIXME!
          [p.dst, p.src, p.payload.index(0), p.id, p.payload.index(1)] 
          #[p.dst, p.src, p.payload[0], p.id, p.payload[1]] 
        end
      end

      # bootstrap slaves: they are not required to replicate data to the source.
      pipe_out <= jrep.map { |s, m| [m.peer, @addy, s.reqid, [s.key, s.value]] if s.client == m.peer }
    end

end
