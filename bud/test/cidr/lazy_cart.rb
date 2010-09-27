require 'rubygems'
require 'bud'

class BasicCartServer < Bud

  def state
    table :cart_action, ['session', 'item', 'action', 'reqid']
    table :action_cnt, ['session', 'item', 'action'] , ['cnt']
   # table :status, ['server', 'client', 'session', 'item'], ['cnt']
    scratch :status, ['server', 'client', 'session', 'item'], ['cnt']
    table :member, ['player']
    table :acked, ['server', 'peer', 'reqid']

    # this was the guard
    #table :checkout, ['server', 'client', 'session']

    scratch :client_action, ['server', 'client', 'session', 'item', 'action', 'reqid']
    scratch :ac, ['session', 'item', 'action', 'reqid']

    channel :ack, 0, ['server', 'peer', 'reqid']
    channel :action, 0, ['server', 'client', 'session', 'item', 'action', 'reqid']
    channel :checkout, 0, ['server', 'client', 'session', 'reqid']
    channel :response, 0, ['client', 'server', 'session', 'item', 'cnt']
    channel :tickler, 0, ['server']
  end
 
  declare
    def saved
      # store actions against the "cart;" that is, the session.
      cart_action <= action.map { |c| print "ACKTON #{c.inspect}\n"; [c.session, c.item, c.action, c.reqid] }

      # PAA - CRASH without the +?  find out why.
      action_cnt <+ cart_action.group([cart_action.session, cart_action.item, cart_action.action], count(cart_action.reqid))
      action_cnt <+ cart_action.map{|a| [a.session, a.item, 'D', 0] unless cart_action.map{|c| [c.session, c.item] if c.action == "D"}.include? [a.session, a.item]}
    end

  declare 
    def acks
      ack <+ action.map {|a| [a.client, a.server, a.reqid] }
      acked <= ack.map{|a| a}
    end

  declare
    def consider
      print "AClen #{action_cnt.length}, COlen #{checkout.length}\n"
      status <= join([action_cnt, action_cnt, checkout]).map do |a1, a2, c| 
        if a1.session == a2.session and a1.item == a2.item and a1.session == c.session and a1.action == "A" and a2.action == "D"
	  print "OK status\n"
          [c.client, c.server, a1.session, a1.item, a1.cnt - a2.cnt] if (a1.cnt - a2.cnt) > 0
        end
      end
      response <+ status.map do|s| 
        print "RESPONSE: #{s.inspect}\n"
	s
      end
    end

  declare
    def replicate
      action <+ join([action, member]).map do |a, m|
        unless acked.map{|ac| [ac.peer, ac.reqid]}.include? [m.player, a.reqid]
          [m.player, a.server, a.session, a.item, a.action, a.reqid]
        end
      end

      #checkout <+ join([checkout, member]).map do |c, m|
      #  [m.player, c.client, c.session]
      #end
    end

  declare
    #def client
    #  action <+ client_action.map{|a| a}
    #end
  # um
end

