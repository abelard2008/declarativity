require 'rubygems'
require 'bud'

class BasicCartServer < Bud

  def state
    table :cart_action, ['session', 'item', 'action', 'reqid']
    table :action_cnt, ['session', 'item', 'action'] , ['cnt']
    table :status, ['session', 'item'], ['cnt']
    table :member, ['player']
    table :acked, ['server', 'peer', 'reqid']

    table :checkout_guard, ['server', 'client', 'session']

    scratch :client_action, ['server', 'client', 'session', 'item', 'action', 'reqid']
    scratch :ac, ['session', 'item', 'action', 'reqid']

    channel :ack, 0, ['server', 'peer', 'reqid']
    channel :action, 0, ['server', 'client', 'session', 'item', 'action', 'reqid']
    channel :checkout, 0, ['server', 'client', 'session']
    channel :response, 0, ['client', 'server', 'session', 'item', 'cnt']
    channel :tickler, 0, ['server']
  end
 
  declare
    def accumulate 
      # store actions against the "cart;" that is, the session.
      cart_action <= action.map { |c| [c.session, c.item, c.action, c.reqid] }

      # do I have to split the join-agg into 2 strata?
      j = join [ cart_action, checkout_guard ], [cart_action.session, checkout_guard.session]
      ac <= j.map do | a, c | 
        [a.session, a.item, a.action, a.reqid] #if a.session = c.session
      end

      checkout_guard <= checkout.map{|c| c}

      action_cnt <= ac.group([ac.session, ac.item, ac.action], count(ac.reqid))
      action_cnt <= ac.map{|a| [a.session, a.item, 'D', 0] unless ac.map{|c| [c.session, c.item] if c.action == "D"}.include? [a.session, a.item]}
    end

  declare 
    def acks
      #ack <+ action.map {|a| [a.client, a.server, a.reqid] }
      #acked <= ack.map{|a| a}
    end

  declare
    def consider
      status <= join([action_cnt, action_cnt, checkout_guard]).map do |a1, a2, c| 
        if a1.session == a2.session and a1.item == a2.item and a1.session == c.session and a1.action == "A" and a2.action == "D"
          [a1.session, a1.item, a1.cnt - a2.cnt] if (a1.cnt - a2.cnt) > 0
        end
      end
    end
  declare 
    def finish
      response <= join([status, checkout_guard], [status.session, checkout_guard.session]).map do |s, c| 
        print "RESPONSE: #{s.inspect}\n"
        #[c.client, c.server, s.session, s.item, s.cnt]
      end
    end
  declare
    def replicate
      action <+ join([action, member]).map do |a, m|
        #unless acked.map{|ac| [ac.peer, ac.reqid]}.include? [m.player, a.reqid]
          [m.player, a.server, a.session, a.item, a.action, a.reqid]
        #end
      end

      checkout <+ join([checkout_guard, member]).map do |c, m|
        [m.player, c.client, c.session]
      end
    end

  declare
    def client
      action <+ client_action.map{|a| a}
    end
  # um
end

